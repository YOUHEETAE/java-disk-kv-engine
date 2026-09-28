package geoindex.storage;

import geoindex.metric.EngineMetrics;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
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
 *   그래서 파일 크기가 실제로 쓴 페이지 수에만 비례한다 — 페이지 4KB + 색인 엔트리 16B.
 *
 * 파일 배치
 *   [0 ~ 3]      매직
 *   [4 ~ 7]      포맷 버전
 *   [8 ~ 15]     예약
 *   [16 ~ ]      페이지 데이터 — 4KB 씩 이어 붙인다
 *   [footer]     (pageId 8B + offset 8B) × 페이지 수, pageId 오름차순
 *   [끝 12B]     페이지 수(long) + 매직(int)
 *
 * 매핑을 파일 끝에 두는 이유
 *   앞에 두려면 데이터를 쓰기 전에 자리를 예약해야 하고, 예약한 크기가 그대로 페이지 수
 *   상한이 된다. 끝에 두면 데이터를 다 쓴 뒤에 크기가 정해지므로 상한이 없다.
 *   대신 읽는 쪽이 footer 의 시작 위치를 모르게 되어, 파일 맨 끝에 페이지 수를 적어
 *   거기서 거꾸로 찾아간다.
 *
 * 완성 표식
 *   매직이 파일의 마지막 4바이트라는 것이 핵심이다. 그 자리에 매직이 있다는 것은 그 앞의
 *   footer 가 끝까지 쓰였다는 뜻이고, 쓰다가 죽은 파일은 이 표식이 없어 기동 때 거부된다.
 *   표식이지 체크섬은 아니다 — 프로세스 사망은 잡지만 부분 쓰기나 비트 썩음은 잡지 않는다.
 *   페이지 데이터와 footer 가 어긋난 채로 이 표식이 남는 경우를 막는 것은 savePage 쪽 일이다.
 */
public class DiskManager {

    /**
     * 헤더가 차지하는 자리. 실제로 쓰는 것은 앞 8바이트(매직·버전)뿐이고 뒤 8은 비워 둔다.
     * 지금 잡아 두는 이유: 나중에 필드를 더하려면 DATA_OFFSET 이 밀리고, 그러면 이미 쓴
     * 파일 전부가 못 읽는 파일이 된다. 빈 자리는 페이지 하나보다 싸다.
     */
    private static final int HEADER_SIZE = 16;
    /**
     * 파일 맨 끝 = 페이지 수(8) + 매직(4). writeFooter 가 엔트리 뒤에 쓰는 바이트 수와
     * 반드시 같아야 한다 — 어긋나면 페이지 수를 엉뚱한 위치에서 읽어 footer 시작점이 틀어진다.
     */
    private static final int TRAILER_SIZE = 12;
    private static final int ENTRY_SIZE   = 16;            // 매핑 엔트리 하나 = pageId(8) + offset(8)

    private static final int OFFSET_MAGIC   = 0;
    private static final long DATA_OFFSET = HEADER_SIZE;               // 페이지 데이터 시작

    /** "MDB1". 0 이면 안 된다 — 0 으로 채워진 파일이 검증을 통과한다. */
    private static final int MAGIC = 0x4D444231;
    /** 파일에 적지 않는 값(PAGE_SIZE · BITS_PER_AXIS)을 바꾸면 이 번호도 올려야 한다. */
    private static final int FORMAT_VERSION = 1;

    private static final Logger log = Logger.getLogger(DiskManager.class.getName());

    private RandomAccessFile dbFile;
    private final String filePath;
    private final Map<Long, Long> pageMap  = new HashMap<>();
    private long nextDataOffset = DATA_OFFSET;
    private boolean footerOnDisk = false;

    private final EngineMetrics  engineMetrics;

    public DiskManager(String filePath, EngineMetrics engineMetrics) {
        this.engineMetrics = engineMetrics;
        this.filePath = filePath;
        boolean loaded = false;
        try {
            this.dbFile = new RandomAccessFile(filePath, "rw");
            // 파일 생애의 갈림길. 없던 파일이면 만들고, 있던 파일이면 지난 실행의 매핑을 되살린다.
            if (dbFile.length() == 0) initializeNewFile();
            else                      readFooter();
            loaded = true;
        } catch (IOException e) {
            throw new RuntimeException("DiskManager init failed", e);
        }finally {
            if (!loaded) {
                closeFileQuietly();
            }
        }
    }


    /**
     * 빈 파일을 쓸 수 있는 상태로 만든다. 페이지가 0장이어도 footer 를 써 두는 이유는
     * "만든 적 있다"와 "만들다 죽었다"를 구별하기 위해서다 — 헤더만 있는 파일은 다음 기동에서
     * 손상으로 거부되어야 하고, 페이지 0장인 파일은 조용히 열려야 한다.
     */
    private void initializeNewFile() throws IOException {
        writeHeader();
        writeFooter();
    }

    /**
     * 파일 끝의 색인을 읽어 pageMap 을 복원한다. 재시작 전용 경로다 — 한 프로세스가 사는
     * 동안에는 savePage 가 pageMap 을 채우므로, 이 메서드는 생성자에서 한 번만 돈다.
     * footer 를 파일에 쓰는 이유가 이 복원 하나다.
     *
     * nextDataOffset 을 파일 길이가 아니라 페이지 수로 계산하는 이유: 파일 길이에는 footer 가
     * 포함되어 있다. 길이를 그대로 쓰면 재시작 뒤 첫 페이지가 옛 footer 뒤에 붙어 파일에
     * 쓰레기 구간이 생긴다.
     *
     * 루프 안에 seek 이 없는 것은 readLong 이 파일 포인터를 전진시키기 때문이다.
     * 시작점만 한 번 맞추면 나머지는 순차 읽기다.
     */
    private void readFooter() throws IOException {
        long len = dbFile.length();
        requireCompleteFile(len);

        dbFile.seek(len - TRAILER_SIZE);
        long pageCount = dbFile.readLong();

        dbFile.seek(len - TRAILER_SIZE - pageCount * ENTRY_SIZE);

        for (int i = 0; i < pageCount; i++) {
            long pageId = dbFile.readLong();
            long offset = dbFile.readLong();
            pageMap.put(pageId, offset);
        }

        nextDataOffset = DATA_OFFSET + pageCount * Page.PAGE_SIZE;

        footerOnDisk = true;
    }

    /**
     * 이 파일이 우리 포맷이고 끝까지 쓰였는지 본다. 통과하지 못하면 던진다 —
     * 빈 pageMap 으로 넘어가면 데이터가 사라진 것처럼 보이고, 그 상태의 put 이 진짜로 덮어쓴다.
     *
     * 길이 검사를 먼저 하는 이유가 둘이다. 0 바이트(새 파일)와 잘린 파일을 구별해 주고,
     * 아래 seek 들이 음수가 되는 경로를 없앤다. 그게 없으면 잘린 파일이
     * "Negative seek offset" 으로 터져 원인이 메시지에 남지 않는다.
     *
     * 메시지를 넷으로 나눈 이유: 읽는 사람이 할 일이 다르다. 잘림·표식 없음은 재구축,
     * 앞 매직 불일치는 경로 확인(남의 파일을 열고 있다), 버전 불일치는 재구축이다.
     * filePath 를 넣는 것은 rebuild 가 임시 파일에도 같은 생성자를 쓰기 때문이다.
     */
    private void requireCompleteFile(long len) throws IOException {
        if (len < HEADER_SIZE + TRAILER_SIZE) {
            throw new IllegalStateException(
                    "incomplete index file: " + filePath + " is " + len + " bytes, "
                            + "a complete file is at least " + (HEADER_SIZE + TRAILER_SIZE));
        }

        dbFile.seek(OFFSET_MAGIC);
        int magic = dbFile.readInt();
        if (magic != MAGIC) {
            throw new IllegalStateException(
                    "not an index file: " + filePath + " starts with 0x"
                            + Integer.toHexString(magic) + ", expected 0x" + Integer.toHexString(MAGIC));
        }

        int version = dbFile.readInt();
        if (version != FORMAT_VERSION) {
            throw new IllegalStateException(
                    "unsupported format version " + version + " in " + filePath
                            + ", this build writes " + FORMAT_VERSION + "; rebuild the index");
        }

        dbFile.seek(len - Integer.BYTES);
        int endMagic = dbFile.readInt();
        if (endMagic != MAGIC) {
            throw new IllegalStateException(
                    "incomplete index file: " + filePath + " has no end marker (found 0x"
                            + Integer.toHexString(endMagic) + ") — the writer did not finish");
        }
    }

    private void writeHeader() throws IOException {
        dbFile.seek(OFFSET_MAGIC);
        dbFile.writeInt(MAGIC);
        dbFile.writeInt(FORMAT_VERSION);
    }

    /**
     * 지금 pageMap 에 있는 것으로 footer 를 통째로 다시 쓴다. 엔트리를 하나씩 끼워 넣지 않는
     * 이유: pageMap 이 메모리에 완전한 상태로 있으니 전부 다시 쓰는 편이 싸고, 파일과 메모리가
     * 어긋날 여지가 없다.
     *
     * 정렬 덕에 같은 pageMap 이면 같은 바이트가 나온다 — 읽는 쪽이 순서를 요구하지는 않는다.
     *
     * setLength 로 끝내는 이유: 옛 파일이 더 길었으면 새 footer 뒤에 옛 꼬리가 남는다.
     * 그 꼬리의 마지막 4바이트가 옛 매직이면 다음 기동이 그것을 완성 표식으로 읽는다.
     */
    private void writeFooter() throws IOException {
        dbFile.seek(nextDataOffset);
        List<Map.Entry<Long, Long>> entries = pageMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        for(Map.Entry<Long, Long> entry : entries){
            Long pageId = entry.getKey();
            Long offset = entry.getValue();
            dbFile.writeLong(pageId);
            dbFile.writeLong(offset);
        }
        dbFile.writeLong(pageMap.size());
        dbFile.writeInt(MAGIC);
        dbFile.setLength(dbFile.getFilePointer());

        footerOnDisk = true;
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
    public synchronized Page loadPage(long pageId) {
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
     * 처음 보는 pageId 면 데이터 끝에 이어 붙이고 offset 을 pageMap 에 남기고,
     * 이미 있는 pageId 면 그 자리에 덮어쓴다. 어느 쪽이든 파일 쓰기는 페이지 하나뿐이다 —
     * 매핑은 메모리에만 쌓이고, 파일에는 배치 끝에서 footer 가 한 번에 기록한다.
     *
     * 그래서 이 메서드만 돌고 footer 를 쓰지 못한 파일은 미완성이다. 그것이 완성된 것처럼
     * 보이지 않게 하는 것이 아래 setLength 의 역할이다.
     *
     * synchronized 인 이유: 파일 포인터뿐 아니라 nextDataOffset · pageMap · footerOnDisk 가
     * 함께 갱신된다. 나뉘어 실행되면 두 스레드가 같은 오프셋을 할당받아 서로를 덮어쓴다.
     */
    public synchronized void savePage(Page page) {
        engineMetrics.incrementPageWriteCount();
        try {
            long pageId = page.getPageId();
            Long offset = pageMap.get(pageId);

            if (offset == null) {
                // 새 페이지가 붙는 순간, 파일 끝의 footer 는 지금 데이터와 짝이 맞지 않는다.
                // 지우지 않으면 옛 footer 가 새로 쓴 페이지보다 길 때 끝의 매직이 살아남아,
                // 여기서 죽은 파일이 완성된 것으로 통과하고 엉뚱한 매핑이 복원된다.
                // 배치의 첫 새 페이지에서 한 번만 잘린다. 덮어쓰기(offset != null)는
                // pageId·offset 이 그대로라 footer 가 여전히 맞으므로 자르지 않는다.
                if(footerOnDisk) {
                    dbFile.setLength(nextDataOffset);
                    footerOnDisk = false;
                }

                // 새 페이지 → 데이터 끝에 추가
                offset = nextDataOffset;
                pageMap.put(pageId, offset);

                nextDataOffset += Page.PAGE_SIZE;
            }

            // 페이지 데이터 기록
            dbFile.seek(offset);
            dbFile.write(page.getData(), 0, Page.PAGE_SIZE);

        } catch (IOException e) {
            throw new RuntimeException("savePage failed: pageId=" + page.getPageId(), e);
        }
    }

    /**
     * 파일 핸들을 반납한다. 데이터 완성은 이 메서드의 일이 아니다 — sealIndex 가 하고,
     * flush 가 그것을 부른다. 그래서 close 를 부르지 않아도 파일은 이미 열 수 있는 상태다.
     *
     * synchronized 인 이유: loadPage · savePage 가 쓰고 있는 dbFile 을 닫는다.
     * 종료와 처리 중인 요청이 겹치면 seek 과 readFully 사이에서 핸들이 사라진다.
     */
    public synchronized void close() {
        try {
            dbFile.close();
        } catch (IOException e) {
            throw new RuntimeException("close failed", e);
        }
    }

    /**
     * 지금 pageMap 에 있는 것으로 파일 끝 색인을 다시 쓴다. 이 호출이 끝나면 파일은 완성 상태다.
     *
     * flush 가 페이지를 다 쓴 뒤에 부른다 — 색인은 "지금 파일에 든 페이지 목록"이라
     * 그 배치가 끝나는 지점에서만 정확하다. close 에만 두면 flush 로 끝내는 호출자가
     * 미완성 파일을 남긴다.
     *
     * 이미 짝이 맞는 footer 가 있으면 다시 쓰지 않는다(footerOnDisk). 덕분에 배치 끝이라고
     * 판단하는 곳마다 부담 없이 부를 수 있다 — 검색만 한 flush 나, 이미 seal 된 파일을
     * 넘겨받은 rebuild 에서는 파일 쓰기가 0회다.
     */
    public synchronized void sealIndex() {
        if (footerOnDisk) return;
        try {
            writeFooter();
        } catch (IOException e) {
            throw new RuntimeException("sealIndex failed", e);
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
        deleteOrThrow(Path.of(tempPath));
        boolean swapped = false;
        boolean dbFileClosed =  false;
        DiskManager tempDm = null;
        try {
            // 1. 임시 파일에 새 DiskManager 생성
            tempDm = new DiskManager(tempPath, engineMetrics);

            // 2. 임시 파일에 데이터 구축 (기존 파일 살아있음)
            loader.load(tempDm);

            // 3. 색인 완성. 로더가 savePage 만 부르고 끝냈으면 여기가 유일한 seal 지점이다 —
            //    flush 는 CacheManager 의 메서드라, DiskManager 만 받은 로더에는 없다.
            //    rename 으로 들어가는 파일은 다시 열 수 있어야 한다("옛것 아니면 새것").
            //    CacheManager 를 거친 로더면 그쪽 flush 가 이미 seal 했으므로 no-op 이다.
            tempDm.sealIndex();

            // 4. 임시 파일 닫기
            tempDm.close();

            // ── 파일 교체 구간. loadPage / savePage 와 같은 모니터를 잡는다 ──
            // 이 안에는 dbFile 이 닫혀 있고 pageMap 이 비어 있는 순간이 있다.
            // 잠그지 않으면 요청 스레드가
            //   (1) 닫힌 파일을 읽어 예외가 나거나
            //   (2) 빈 pageMap 을 보고 "그런 페이지 없음"으로 답한다 — 예외 없는 조용한 누락
            // 오래 걸리는 적재(loader.load)는 일부러 이 밖에 두어 무중단을 유지한다.
            synchronized (this) {

                // 5. 기존 파일 닫기
                dbFile.close();

                dbFileClosed = true;

                // 6. atomic rename
                Files.move(
                        Path.of(tempPath),
                        Path.of(filePath),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );

                // 7. 새 파일 열기 + 내부 상태 교체
                //    새 파일의 매핑은 tempDm 이 이미 갖고 있다 — 적재하며 savePage 가 채웠다.
                //    파일에서 다시 읽으면(readFooter) 락 구간에 파일 읽기가 들어오고,
                //    그 도중 실패하면 pageMap 이 반쯤 찬 상태로 남는다.
                //    메모리 복사는 실패하지 않으므로 그 경로 자체를 없앤다.
                //    footerOnDisk 도 함께 가져온다 — 방금 seal 한 파일이라 true 다.
                //    false 로 남으면 다음 savePage 가 옛 footer 를 자르지 않는다.
                dbFile = new RandomAccessFile(filePath, "rw");
                pageMap.clear();
                pageMap.putAll(tempDm.pageMap);
                footerOnDisk = tempDm.footerOnDisk;
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

    /**
     * 임시 파일 자리를 비운다. 못 비우면 던진다 — 잔재 위에서 재구축을 시작하면
     * 지난 실행의 페이지가 pageMap 에 실려 새 인덱스에 섞인다. 예외도 안 나고,
     * 검색이 삭제된 레코드를 돌려주는 상태가 된다.
     *
     * finally 쪽 정리는 deleteQuietly 를 쓴다. 거기서 던지면 원래 실패 원인을 덮는다.
     */
    private void deleteOrThrow(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new RuntimeException(
                    "cannot start rebuild: leftover temp file " + path
                            + " could not be deleted — another process may hold it open", e);
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

    /**
     * 생성이 무산될 때 열어 둔 핸들만 닫는다.
     *
     * 조용한 이유: 여기서 던지면 원래 실패 원인(손상 진단)을 덮는다.
     * 닫지 않으면 Windows 가 그 파일을 지우지 못해, 파일을 버리고 다시 만드는 복구까지 막힌다.
     */
    private void closeFileQuietly() {
        if(dbFile == null) return;
        try {
            dbFile.close();
        } catch (IOException e) {
            log.warning("파일 닫기 실패: " + filePath + " — " + e.getMessage());
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
