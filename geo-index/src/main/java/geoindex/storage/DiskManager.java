package geoindex.storage;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

public class DiskManager {

    private static final int MAX_ENTRIES  = 100_000;       // 최대 페이지 수
    private static final int ENTRY_SIZE   = 12;            // pageId(4) + offset(8)
    private static final int COUNT_OFFSET = 0;             // 엔트리 수 위치
    private static final int MAP_OFFSET   = 4;             // 매핑 테이블 시작
    private static final long DATA_OFFSET =                // 데이터 시작
            MAP_OFFSET + (long) MAX_ENTRIES * ENTRY_SIZE;

    private RandomAccessFile dbFile;
    private final String filePath;
    private final Map<Integer, Long> pageMap  = new HashMap<>();
    private final Map<Integer, Integer> entryIndex = new HashMap<>(); // pageId → 헤더 내 인덱스
    private int entryCount = 0;
    private long nextDataOffset = DATA_OFFSET;

    public DiskManager(String filePath) {
        this.filePath = filePath;
        try {
            this.dbFile = new RandomAccessFile(filePath, "rw");
            loadPageMap();
        } catch (IOException e) {
            throw new RuntimeException("DiskManager init failed", e);
        }
    }

    private void loadPageMap() throws IOException {
        if (dbFile.length() < MAP_OFFSET) {
            // 새 파일 → 엔트리 수 0으로 초기화
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
            entryIndex.put(pageId, i);
        }

        nextDataOffset = Math.max(DATA_OFFSET, dbFile.length());
    }

    public synchronized Page readPage(int pageId) {
        Long offset = pageMap.get(pageId);
        if (offset == null) return new Page(pageId);

        try {
            dbFile.seek(offset);
            Page page = new Page(pageId);
            dbFile.readFully(page.getData());
            return page;
        } catch (IOException e) {
            throw new RuntimeException("readPage failed: pageId=" + pageId, e);
        }
    }

    public synchronized void writePage(Page page) {
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
                entryIndex.put(pageId, entryCount);

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
            throw new RuntimeException("writePage failed: pageId=" + page.getPageId(), e);
        }
    }

    public void close() {
        try {
            dbFile.close();
        } catch (IOException e) {
            throw new RuntimeException("close failed", e);
        }
    }
    public void rebuild(DiskManagerLoader loader) {
        String tempPath = filePath + ".new";
        try {
            // 1. 임시 파일에 새 DiskManager 생성
            DiskManager tempDm = new DiskManager(tempPath);

            // 2. 임시 파일에 데이터 구축 (기존 파일 살아있음)
            loader.load(tempDm);

            // 3. 임시 파일 닫기
            tempDm.close();

            // 4. 기존 파일 닫기
            dbFile.close();

            // 5. atomic rename
            Files.move(
                    Path.of(tempPath),
                    Path.of(filePath),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );

            // 6. 새 파일 열기 + 내부 상태 교체
            dbFile = new RandomAccessFile(filePath, "rw");
            pageMap.clear();
            entryIndex.clear();
            entryCount     = 0;
            nextDataOffset = DATA_OFFSET;
            loadPageMap();

        } catch (IOException e) {
            throw new RuntimeException("DiskManager rebuild failed", e);
        }
    }

    @FunctionalInterface
    public interface DiskManagerLoader {
        void load(DiskManager dm);
    }

}
