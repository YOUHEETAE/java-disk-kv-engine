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
import java.util.stream.Collectors;

import java.util.function.Function;
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

    public void close(){
        spatialRecordManager.close();
    }

    public SpatialCacheEngine(SpatialRecordManager spatialRecordManager,
                              CachePolicy cachePolicy, EngineMetrics engineMetrics) {
        this(spatialRecordManager, cachePolicy, engineMetrics, null);
    }

    private List<T> getOrLoad(int pageId, List<String> codes, Function<List<String>, List<T>> loader) {
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

    public List<T> search (double lat, double lng, double radiusKm, Function<List<String>, Map<String, T>> batchLoader) {
        Map<Integer, List<String>> codesByPageId = spatialRecordManager.searchRadiusCodesByPageId(lat, lng, radiusKm);
        
        PageLoadState<T> state = new PageLoadState<>();

        classifyPageStates(codesByPageId, state);

        // 내 몫을 전부 complete 한 뒤에야 남의 future 를 join 한다. 이 순서가 데드락이 없는
        // 유일한 근거다 — A 가 page1 승자·page2 패자, B 가 그 반대일 때 둘 다 자기 몫을
        // 먼저 끝내므로 사이클이 생기지 않는다. loadPages 와 assembleResults 를 바꾸면 깨진다.
        loadPages(state, batchLoader);

        return assembleResults(codesByPageId, state);
    }

    private void classifyPageStates(Map<Integer, List<String>> codesByPageId, PageLoadState<T> state) {
        for(Map.Entry<Integer, List<String>> entry : codesByPageId.entrySet()){
            int pageId = entry.getKey();
            List<String> codes = entry.getValue();

            PageResult<T> result = pageCacheStore.getOrMiss(pageId, codes);

            if (result.isHit()) {
                state.putReadyPage(pageId, result.getCached());
                continue;
            }

            CompletableFuture<List<T>> future = new CompletableFuture<>();
            CompletableFuture<List<T>> existing = pendingLoads.putIfAbsent(pageId, future);

            if(existing == null){
                recheckAndClassify(pageId, codes, state, future);
            } else {
                state.addWaitingFuture(pageId, existing);
            }
        }
    }

    private void recheckAndClassify(int pageId,
                                   List<String> codes,
                                   PageLoadState<T> state,
                                   CompletableFuture<List<T>> future){
        PageResult<T> recheck = pageCacheStore.getOrMiss(pageId, codes);
        if (recheck.isHit()) {
            future.complete(recheck.getCached());
            pendingLoads.remove(pageId, future);
            state.putReadyPage(pageId, recheck.getCached());
        } else {
            state.addPageToLoad(pageId, codes);
            state.registerMyFuture(pageId, future);
        }
    }

    private void loadPages(PageLoadState<T> state, Function<List<String>, Map<String, T>> batchLoader) {
        if(!state.hasPageToLoad()) return;

        try {
            List<String> codesToLoad = state.getCodesToLoad();
            Map<String, T> loaded = batchLoader.apply(codesToLoad);

            state.forEachPageToLoad((pageId, codes) -> {
                List<T> pageData = codes.stream()
                        .map(loaded::get)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                putCache(pageId, pageData);
                state.putReadyPage(pageId, pageData);
                state.getMyFuture(pageId).complete(pageData);
            });
        } catch (Exception e){
            state.propagateFailure(e);
            throw e;
        } finally {
            // 정상 경로에서는 위 루프가 전부 complete 했으므로 no-op 이다. catch(Exception) 을
            // 건너뛰는 Error 경로에서만 실제로 동작해, existing 을 쥔 대기 스레드를 풀어준다.
            state.forEachMyFuture((id, f) -> {
                f.completeExceptionally(new IllegalStateException("loader did not complete: " + id));
                pendingLoads.remove(id, f);
            });
        }
    }

    private List<T> assembleResults(Map<Integer, List<String>> codesByPageId, PageLoadState<T> state) {
        List<T> result = new ArrayList<>();

        for(Map.Entry<Integer, List<String>> entry : codesByPageId.entrySet()){
            int pageId = entry.getKey();

            if(state.hasReadyPage(pageId)){
                result.addAll(state.getReadyPage(pageId));
            } else if (state.hasWaitingFuture(pageId)) {
                result.addAll(state.getWaitingFuture(pageId).join());
            }
        }
        return result;
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
                spatialRecordManager.getUsedOverflowPageCount(),
                spatialRecordManager.getUsedPageCount()
        );
    }

    // -------------------------------------------------------------------------
    // warmup
    // -------------------------------------------------------------------------

    public List<Integer> getWarmupCandidates(int n) {
        return warmupStore.getTopPageIds(n);
    }

    public Map<Integer, List<String>> getWarmupTargets(int n) {
        return warmupStore.getTopPageIds(n).stream()
                .collect(Collectors.toMap(
                        pageId -> pageId,
                        spatialRecordManager::getAllCodesByPageId
                ));
    }

    public void persistWarmup() {
        warmupStore.saveHitCounts();
    }

}