package geoindex.storage;

import geoindex.metric.EngineMetrics;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 페이지를 파일에 읽고 쓰는 계층. pageId → 파일 오프셋 매핑을 소유한다.
 *
 * 왜 매핑 테이블이 필요한가
 *   교과서적인 페이지 파일은 offset = pageId × 4096 으로 위치를 계산한다.
 *   그러려면 pageId 가 0,1,2… 로 조밀해야 하는데, 이 엔진의 pageId 는
 *   GeoHash Morton 코드라 2^30 공간에 흩어져 있다 (강남역 = 971,394,252).
 *   곱셈으로 계산하면 파일이 TB 급이 되므로, 위치를 계산하지 않고 조회한다.
 *   그래서 파일 크기가 "실제로 쓴 페이지 수 × 4KB"에만 비례한다.
 *
 * 파일 배치
 *   [0 ~ 3]           entryCount
 *   [4 ~ 1,200,003]   매핑 테이블 — (pageId 4B + offset 8B) × MAX_ENTRIES
 *   [1,200,004 ~ ]    페이지 데이터, 4KB 씩 뒤에 이어 붙인다
 *
 * 매핑 테이블을 파일 앞에 고정 크기로 잡은 대가로 페이지 수 상한이 생겼지만,
 * 데이터 시작 위치(DATA_OFFSET)가 컴파일 타임 상수가 되어 코드가 단순해진다.
 * 실사용이 상한에 가까워지면 테이블을 파일 끝(footer)으로 옮기는 편이 낫다.
 */
public class DiskManager {

    private static final int MAX_ENTRIES  = 100_000;       // 담을 수 있는 최대 페이지 수 = 매핑 테이블 칸 수
    private static final int ENTRY_SIZE   = 12;            // 매핑 엔트리 하나 = pageId(4) + offset(8)
    private static final int COUNT_OFFSET = 0;             // entryCount 위치
    private static final int MAP_OFFSET   = 4;             // 매핑 테이블 시작
    private static final long DATA_OFFSET =                // 페이지 데이터 시작
            MAP_OFFSET + (long) MAX_ENTRIES * ENTRY_SIZE;
    private static final Logger log = Logger.getLogger(DiskManager.class.getName());

    private RandomAccessFile dbFile;
    private final String filePath;
    private final Map<Integer, Long> pageMap  = new HashMap<>();
    private int entryCount = 0;
    private long nextDataOffset = DATA_OFFSET;

    private final EngineMetrics  engineMetrics;

    public DiskManager(String filePath, EngineMetrics engineMetrics) {
        this.engineMetrics = engineMetrics;
        this.filePath = filePath;
        try {
            this.dbFile = new RandomAccessFile(filePath, "rw");
            loadMappingTable();
        } catch (IOException e) {
            throw new RuntimeException("DiskManager init failed", e);
        }
    }


    private void loadMappingTable() throws IOException {
        if (dbFile.length() < MAP_OFFSET) {
            dbFile.seek(COUNT_OFFSET);
            dbFile.writeInt(0);
            return;
        }

        dbFile.seek(COUNT_OFFSET);
        entryCount = dbFile.readInt();

        for (int i = 0; i < entryCount; i++) {
            dbFile.seek(MAP_OFFSET + (long) i * ENTRY_SIZE);
            int pageId = dbFile.readInt();
            long offset = dbFile.readLong();
            pageMap.put(pageId, offset);
        }

        nextDataOffset = Math.max(DATA_OFFSET, dbFile.length());
    }

    /**
     * pageId 로 페이지를 읽는다. 파일에 없으면 null — 없는 것을 만들어내지 않는다.
     *
     * 없을 때 페이지가 필요한지는 파일과 무관한 정책이라 버퍼 계층이 정한다.
     * CacheManager 의 findPage(없으면 null) / getOrCreatePage(없으면 생성) 가 그 두 갈래다.
     * 여기서 빈 Page 를 돌려주면 두 갈래가 하나로 합쳐져, 읽기가 쓰기의 부작용을 물려받는다.
     *
     * 집계를 null 검사 뒤에 두는 이유: pageReadCount 가 실제 디스크 접근만 세도록.
     *
     * synchronized 인 이유: RandomAccessFile 은 내부 파일 포인터를 공유한다.
     * seek 과 readFully 사이에 다른 스레드가 seek 하면 그 위치를 읽어버린다.
     * 두 호출이 한 덩어리로 묶여야 한다.
     */
    public synchronized Page loadPage(int pageId) {
        Long offset = pageMap.get(pageId);
        if (offset == null) return null;
        engineMetrics.incrementPageReadCount();
        try {
            dbFile.seek(offset);
            Page page = new Page(pageId);
            dbFile.readFully(page.getData());
            return page;
        } catch (IOException e) {
            throw new RuntimeException("loadPage failed: pageId=" + pageId, e);
        }
    }

    /**
     * 페이지를 파일에 쓴다.
     * 처음 보는 pageId 면 매핑 테이블에 엔트리를 추가하고 데이터 끝에 이어 붙이고,
     * 이미 있는 pageId 면 그 자리에 덮어쓴다. 헤더는 새 페이지일 때만 건드리므로
     * 갱신 비용이 페이지 수와 무관하게 일정하다.
     *
     * synchronized 인 이유: 파일 포인터뿐 아니라 nextDataOffset · entryCount · pageMap 이
     * 함께 갱신된다. 나뉘어 실행되면 두 스레드가 같은 오프셋을 할당받아 서로를 덮어쓴다.
     */
    public synchronized void savePage(Page page) {
        engineMetrics.incrementPageWriteCount();
        try {
            int pageId = page.getPageId();
            Long offset = pageMap.get(pageId);

            if (offset == null) {
                // 새 페이지 → 데이터 끝에 추가
                if (entryCount >= MAX_ENTRIES) {
                    throw new IllegalStateException("pageMap full: MAX_ENTRIES=" + MAX_ENTRIES);
                }
                offset = nextDataOffset;
                pageMap.put(pageId, offset);

                // 헤더에 새 엔트리 추가
                dbFile.seek(MAP_OFFSET + (long) entryCount * ENTRY_SIZE);
                dbFile.writeInt(pageId);
                dbFile.writeLong(offset);

                entryCount++;
                nextDataOffset += Page.PAGE_SIZE;

                // 엔트리 수 갱신
                dbFile.seek(COUNT_OFFSET);
                dbFile.writeInt(entryCount);
            }

            // 페이지 데이터 기록
            dbFile.seek(offset);
            dbFile.write(page.getData(), 0, Page.PAGE_SIZE);

        } catch (IOException e) {
            throw new RuntimeException("savePage failed: pageId=" + page.getPageId(), e);
        }
    }

    public void close() {
        try {
            dbFile.close();
        } catch (IOException e) {
            throw new RuntimeException("close failed", e);
        }
    }
    /**
     * 인덱스 파일을 통째로 다시 만든다.
     *
     * 재구축 중에도 검색은 계속되어야 하므로, 새 파일을 임시 이름으로 완성한 뒤
     * atomic rename 으로 바꿔치기한다. rename 전까지 기존 파일이 그대로 서비스하고,
     * 프로세스가 언제 죽어도 파일은 옛것 아니면 새것이지 중간 상태가 없다.
     *
     * 정리를 catch 가 아니라 finally 에 둔 이유:
     *   loader 가 던지는 예외는 대부분 unchecked 라 catch(IOException) 으로 잡히지 않는다.
     *   그러면 임시 파일과 핸들이 남고, 열린 핸들 때문에 삭제까지 실패한다.
     * 시작할 때도 한 번 지우는 이유:
     *   프로세스가 죽어 finally 조차 돌지 못한 경우의 잔재를 치우기 위해서다.
     */
    public void rebuild(DiskManagerLoader loader) {
        String tempPath = filePath + ".new";
        deleteQuietly(Path.of(tempPath));
        boolean swapped = false;
        boolean dbFileClosed =  false;
        DiskManager tempDm = null;
        try {
            // 1. 임시 파일에 새 DiskManager 생성
            tempDm = new DiskManager(tempPath, engineMetrics);

            // 2. 임시 파일에 데이터 구축 (기존 파일 살아있음)
            loader.load(tempDm);

            // 3. 임시 파일 닫기
            tempDm.close();

            // ── 파일 교체 구간. loadPage / savePage 와 같은 모니터를 잡는다 ──
            // 이 안에는 dbFile 이 닫혀 있고 pageMap 이 비어 있는 순간이 있다.
            // 잠그지 않으면 요청 스레드가
            //   (1) 닫힌 파일을 읽어 예외가 나거나
            //   (2) 빈 pageMap 을 보고 "그런 페이지 없음"으로 답한다 — 예외 없는 조용한 누락
            // 오래 걸리는 적재(loader.load)는 일부러 이 밖에 두어 무중단을 유지한다.
            synchronized (this) {

                // 4. 기존 파일 닫기
                dbFile.close();

                dbFileClosed = true;

                // 5. atomic rename
                Files.move(
                        Path.of(tempPath),
                        Path.of(filePath),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );

                // 6. 새 파일 열기 + 내부 상태 교체
                //    새 파일의 매핑은 tempDm 이 이미 갖고 있다 — 적재하며 savePage 가 채웠다.
                //    파일에서 다시 읽으면(loadMappingTable) 엔트리 수만큼 seek 이 발생해
                //    락 구간이 길어지고, 그 도중 실패하면 pageMap 이 반쯤 찬 상태로 남는다.
                //    메모리 복사는 실패하지 않으므로 그 경로 자체를 없앤다.
                dbFile = new RandomAccessFile(filePath, "rw");
                pageMap.clear();
                pageMap.putAll(tempDm.pageMap);
                entryCount = tempDm.entryCount;
                nextDataOffset = tempDm.nextDataOffset;
                swapped = true;
            }

        } catch (IOException e) {
            throw new RuntimeException("rebuild failed", e);
        }finally {
            if(!swapped) {
                closeQuietly(tempDm);
                deleteQuietly(Path.of(tempPath));
                if (dbFileClosed){
                    reopenQuietly();
                }
            }
        }
    }

    /** 실패해도 던지지 않는다 — finally 에서 부르므로, 여기서 예외가 나면 원래 실패 원인을 덮는다. */
    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warning("임시 파일 삭제 실패: " + path + " — " + e.getMessage());
        }
    }

    /**
     * 교체 실패 시 기존 파일을 다시 열어 서비스를 잇는다. 이것까지 실패하면 재시작이 필요하다.
     *
     * 셋 중 여기만 synchronized 인 이유: dbFile 은 loadPage 가 읽는 공유 필드이고,
     * 이 메서드는 finally 에서 호출되어 이미 락 밖이다.
     */
    private synchronized void reopenQuietly() {
        try {
            dbFile = new RandomAccessFile(filePath, "rw");
        } catch (IOException e) {
            log.warning("파일 재오픈 실패: " + filePath + " — " + e.getMessage());
        }
    }

    /** deleteQuietly 보다 먼저 불러야 한다 — Windows 는 열려 있는 파일을 지우지 못한다. */
    private void closeQuietly(DiskManager dm) {
        if(dm == null) return;
        try{
            dm.close();
        }catch(RuntimeException e){
            log.warning("임시 DiskManager 닫기 실패: " + e.getMessage());
        }
    }

    public int getUsedPageCount() {
        return pageMap.size();
    }


    @FunctionalInterface
    public interface DiskManagerLoader {
        void load(DiskManager dm);
    }

}
