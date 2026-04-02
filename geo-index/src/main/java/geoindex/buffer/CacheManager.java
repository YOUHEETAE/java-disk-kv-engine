package geoindex.buffer;

import geoindex.metric.EngineMetrics;
import geoindex.storage.DiskManager;
import geoindex.storage.Page;

import java.util.concurrent.ConcurrentHashMap;

public class CacheManager {
    private final ConcurrentHashMap<Integer, Page> cache;
    private final DiskManager diskManager;
    private final EngineMetrics engineMetrics;
    public CacheManager(DiskManager diskManager, EngineMetrics engineMetrics) {
        this.diskManager = diskManager;
        this.cache = new ConcurrentHashMap<>();
        this.engineMetrics = engineMetrics;
    }



    public Page getPage(int pageId) {
        return cache.computeIfAbsent(pageId, diskManager::readPage);
    }


    public void putPage(Page page) {
        synchronized (page) {
            page.markDirty();
        }
        cache.put(page.getPageId(), page);
    }

    public void flush() {
        engineMetrics.incrementFlushCount();
        for (Page page : cache.values()) {
            synchronized (page) {
                if (page.isDirty()) {
                    diskManager.writePage(page);
                    page.clearDirty();
                    engineMetrics.incrementFlushedPages();
                }
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
            CacheManager tempCm = new CacheManager(tempDm, engineMetrics);
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

    public int getDirtyPageCount() {
        return (int) cache.values().stream().filter(Page::isDirty).count();
    }

    public int getUsedPageCount() {
        return diskManager.getUsedPageCount();
    }
}
