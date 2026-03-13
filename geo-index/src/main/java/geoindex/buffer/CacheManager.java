package geoindex.buffer;

import geoindex.storage.DiskManager;
import geoindex.storage.Page;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

public class CacheManager {
    private final ConcurrentHashMap<Integer, Page> cache;
    private final DiskManager diskManager;
    public CacheManager(DiskManager diskManager) {
        this.diskManager = diskManager;
        this.cache = new ConcurrentHashMap<>();
    }

    public Page getPage(int pageId) {
        return cache.computeIfAbsent(pageId, diskManager::readPage);
    }


    public void putPage(Page page) {
        page.markDirty();
        cache.put(page.getPageId(),page);
    }

    public void flush() {
        for (Page page : cache.values()) {
            if(page.isDirty()){
            diskManager.writePage(page);
            page.clearDirty();
            }
        }
    }

    public void clearCache() {
        cache.clear();
    }

    public void close() {
        flush();
        diskManager.close();
    }
    public void rebuild(CacheManagerLoader loader) {
        // 임시 CacheManager에 데이터 구축 (기존 파일 살아있음)
        diskManager.rebuild(tempDm -> {
            CacheManager tempCm = new CacheManager(tempDm);
            loader.load(tempCm);
            tempCm.flush();   // 임시 파일에 기록
        });

        // rename 완료 후 버퍼 초기화 → 새 파일 기반으로 전환
        cache.clear();
    }

    @FunctionalInterface
    public interface CacheManagerLoader {
        void load(CacheManager cm);
    }
}
