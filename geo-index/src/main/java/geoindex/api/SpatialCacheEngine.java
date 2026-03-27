package geoindex.api;

import geoindex.cache.CacheEntry;
import geoindex.cache.CachePolicy;
import geoindex.cache.PageCacheStore;
import geoindex.metric.EngineMetrics;
import geoindex.metric.MetricsSnapshot;

import java.util.*;
import java.util.function.Consumer;

public class SpatialCacheEngine<T> {


    private final SpatialRecordManager spatialRecordManager;
    private final PageCacheStore<T> pageCacheStore;
    private final EngineMetrics engineMetrics;

    public SpatialCacheEngine(SpatialRecordManager spatialRecordManager, EngineMetrics engineMetrics) {
        this(spatialRecordManager, CachePolicy.DEFAULT, engineMetrics);
    }

    public SpatialCacheEngine(SpatialRecordManager spatialRecordManager, CachePolicy cachePolicy, EngineMetrics engineMetrics) {
        this.spatialRecordManager = spatialRecordManager;
        this.engineMetrics = engineMetrics;
        this.pageCacheStore = new PageCacheStore<>(cachePolicy, engineMetrics);
    }

    // -------------------------------------------------------------------------
    // search() — pageId 조회 + HIT/MISS 판단 위임
    // -------------------------------------------------------------------------

    public List<PageResult<T>> search(double lat, double lng, double radiusKm) {
        Map<Integer, List<String>> codesByPageId = spatialRecordManager.searchRadiusCodesByPageId(lat, lng, radiusKm);
        List<PageResult<T>> results = new ArrayList<>();

        for (Map.Entry<Integer, List<String>> entry : codesByPageId.entrySet()) {
            int pageId = entry.getKey();
            List<String> codes = entry.getValue();
            results.add(pageCacheStore.getOrMiss(pageId, codes));
        }
        return results;
    }

    public void putCache(int pageId, List<T> data) {
        pageCacheStore.put(pageId, data);
    }

    // -------------------------------------------------------------------------
    // rebuild
    // -------------------------------------------------------------------------
    public void rebuild(Consumer<SpatialRecordManager> loader) {
        spatialRecordManager.rebuild(loader);    // 파일 재구축 + atomic rename
        pageCacheStore.clearCache();      // JVM 캐시 초기화
    }

    public CachePolicy getPolicy() {
        return pageCacheStore.getPolicy();
    }

    public long getCacheSize() {
        return pageCacheStore.getCacheSize();
    }
    public boolean isCached(int pageId) {
        return  pageCacheStore.isCached(pageId);
    }

    public void clearCache() {
        pageCacheStore.clearCache();
    }

    // -------------------------------------------------------------------------
    // rebuild
    // -------------------------------------------------------------------------

    public MetricsSnapshot getMetrics() {
        return engineMetrics.snapshot(
                (int) pageCacheStore.getCacheSize(),
                spatialRecordManager.getDirtyPageCount()
        );
    }
}