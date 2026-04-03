package geoindex.api;

import geoindex.cache.CachePolicy;
import geoindex.cache.PageCacheStore;
import geoindex.cache.WarmupStore;
import geoindex.metric.EngineMetrics;
import geoindex.metric.MetricsSnapshot;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class SpatialCacheEngine<T> {


    private final SpatialRecordManager spatialRecordManager;
    private final PageCacheStore<T> pageCacheStore;
    private final EngineMetrics engineMetrics;
    private final WarmupStore warmupStore;
    private final ConcurrentHashMap<Integer, CompletableFuture<List<T>>> pendingLoads = new ConcurrentHashMap<>();

    public SpatialCacheEngine(SpatialRecordManager spatialRecordManager, EngineMetrics engineMetrics) {
        this(spatialRecordManager, CachePolicy.DEFAULT, engineMetrics, null);
    }

    public SpatialCacheEngine(SpatialRecordManager spatialRecordManager, CachePolicy cachePolicy, EngineMetrics engineMetrics,  WarmupStore warmupStore) {
        this.spatialRecordManager = spatialRecordManager;
        this.engineMetrics = engineMetrics;
        this.pageCacheStore = new PageCacheStore<>(cachePolicy, engineMetrics, warmupStore);
        this.warmupStore = warmupStore;
    }

    public SpatialCacheEngine(SpatialRecordManager spatialRecordManager,
                              CachePolicy cachePolicy, EngineMetrics engineMetrics) {
        this(spatialRecordManager, cachePolicy, engineMetrics, null);
    }

    private List<T> getOrLoad(int pageId, List<String> codes, Function<List<String>, List<T>> loader){
        PageResult<T> result = pageCacheStore.getOrMiss(pageId, codes);
        if (result.isHit()) return result.getCached();

        CompletableFuture<List<T>> future = new CompletableFuture<>();
        CompletableFuture<List<T>> existing = pendingLoads.putIfAbsent(pageId, future);
        if(existing != null) return  existing.join();
        PageResult<T> recheck = pageCacheStore.getOrMiss(pageId, codes);
        if (recheck.isHit()) {
            future.complete(recheck.getCached());
            pendingLoads.remove(pageId);
            return recheck.getCached();
        }
        try{
            List<T> data = loader.apply(codes);
            pageCacheStore.put(pageId, data);
            future.complete(data);
            return data;

        } catch (Exception e){
            future.completeExceptionally(e);
            throw e;
        }finally {
            pendingLoads.remove(pageId);
        }

    }

    public List<List<T>> search (double lat, double lng, double radiusKm, Function<List<String>, List<T>> loader){
        Map<Integer, List<String>> codesByPageId = spatialRecordManager.searchRadiusCodesByPageId(lat, lng, radiusKm);

        List<List<T>> results = new ArrayList<>();
        for(Map.Entry<Integer, List<String>> entry : codesByPageId.entrySet()){
            results.add(getOrLoad(entry.getKey(), entry.getValue(), loader));
        }
        return results;
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
    // metric
    // -------------------------------------------------------------------------

    public MetricsSnapshot getMetrics() {
        return engineMetrics.snapshot(
                (int) pageCacheStore.getCacheSize(),
                spatialRecordManager.getDirtyPageCount(),
                spatialRecordManager.getUsedOverflowPageCount()
        );
    }

    public List<Integer> getWarmupCandidates(int n) {
        return warmupStore.getTopPageIds(n);
    }

    public void persistWarmup() {
        warmupStore.persist();
    }


}