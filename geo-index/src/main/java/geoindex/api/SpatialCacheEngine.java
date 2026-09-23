package geoindex.api;

import geoindex.cache.CachePolicy;
import geoindex.cache.PageCacheStore;
import geoindex.cache.WarmupStore;
import geoindex.metric.EngineMetrics;
import geoindex.metric.MetricsSnapshot;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 인덱스와 DB 사이에 서는 캐시 계층. 반경 → pageId 목록까지는 SpatialRecordManager 가 하고,
 * 그 pageId 의 값을 DB 에서 가져와 pageId 단위로 붙잡아 두는 것이 이 클래스다.
 *
 * DB 를 모른다. 값은 호출자가 넘기는 batchLoader 가 가져오고, 이 클래스는 "어느 페이지가
 * 미스인가 · 누가 로드할 것인가 · 결과를 어떻게 조립하나" 만 정한다. 그래서 테스트가 가짜
 * loader 로 호출 횟수를 셀 수 있다.
 *
 * 동시성은 두 겹이다.
 *   pendingLoads   같은 페이지를 여러 스레드가 동시에 미스하면 한 스레드만 DB 를 치고 나머지는
 *                  그 future 를 기다린다 (Thundering Herd 방지). 맵에는 값이 아니라 약속만 넣고
 *                  느린 로딩은 락 밖에서 한다 — computeIfAbsent 로 하면 DB 왕복 내내 bin 락을 쥔다.
 *   generation     캐시를 비운 뒤 뒤늦게 도착한 로딩이 옛 값을 다시 넣는 것을 막는다.
 *
 * 공개 표면 중 운영 경로는 search · rebuild · clearCache · getWarmupTargets · saveWarmup ·
 * getMetrics · close 다. 나머지(putCache · isCached · getCacheSize)는 예열과 테스트가 쓴다.
 */
public class SpatialCacheEngine<T> {

    private final SpatialRecordManager spatialRecordManager;
    private final PageCacheStore<T> pageCacheStore;
    private final EngineMetrics engineMetrics;
    private final WarmupStore warmupStore;

    /** pageId → 그 페이지를 지금 로드 중인 스레드의 약속. 값이 아니라 약속을 담는다. */
    private final ConcurrentHashMap<Integer, CompletableFuture<List<T>>> pendingLoads = new ConcurrentHashMap<>();

    /**
     * 캐시를 비울 때마다 1 오른다. 비우기 전에 시작된 로딩이 뒤늦게 도착해
     * 옛 인덱스의 코드로 읽은 값을 새 캐시에 넣는 것을 막는다.
     */
    private final AtomicInteger generation = new AtomicInteger();

    public SpatialCacheEngine(SpatialRecordManager spatialRecordManager, CachePolicy cachePolicy, EngineMetrics engineMetrics,  WarmupStore warmupStore) {
        this.spatialRecordManager = spatialRecordManager;
        this.engineMetrics = engineMetrics;
        this.pageCacheStore = new PageCacheStore<>(cachePolicy, engineMetrics, warmupStore);
        this.warmupStore = warmupStore;
    }

    /**
     * 반경 검색. 캐시에 없는 페이지의 코드를 모아 batchLoader 를 한 번 부르고, 결과를
     * 인덱스가 준 페이지 순서대로 조립해 돌려준다. 반경 검색 한 번 = DB 왕복 최대 한 번.
     *
     * 세 단계다 — 분류 · 로드 · 조립. 각 pageId 는 "캐시에 있다 / 내가 로드한다 / 남이 로드
     * 중이다" 셋 중 하나이고 PageLoadState 가 그 분류를 든다.
     */
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

    /** 페이지마다 캐시를 판정하고, 미스면 pendingLoads 에서 로드 권한을 겨룬다. */
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

    /**
     * 로드 권한을 얻은 뒤의 double-check. getOrMiss 와 putIfAbsent 사이에 다른 스레드가
     * 로딩을 끝내고 캐시를 채웠을 수 있다 — 그 틈을 안 보면 방금 들어온 값을 두고 DB 를
     * 또 친다. 이미 있으면 future 를 바로 완료시켜 뒤따라온 대기자도 풀어준다.
     */
    private void recheckAndClassify(int pageId,
                                   List<String> codes,
                                   PageLoadState<T> state,
                                   CompletableFuture<List<T>> future){
        // getOrMiss 가 아니다 — 같은 요청의 두 번째 판정이라 메트릭과 접근 기록을 다시 올리면 안 된다
        List<T> cached = pageCacheStore.peekIfCached(pageId);
        if (cached != null) {
            future.complete(cached);
            pendingLoads.remove(pageId, future);
            state.putReadyPage(pageId, cached);
        } else {
            state.addPageToLoad(pageId, codes);
            state.registerMyFuture(pageId, future);
        }
    }

    /**
     * 내가 맡은 페이지들의 코드를 모아 batchLoader 를 한 번 부르고, 페이지별로 나눠 캐시에
     * 넣고 대기자에게 알린다. 실패하면 대기자에게도 같은 실패를 전한다 — 패자들이 각자
     * 재시도하면 herd 가 되돌아온다.
     */
    private void loadPages(PageLoadState<T> state, Function<List<String>, Map<String, T>> batchLoader) {
        if(!state.hasPageToLoad()) return;
        int gen = generation.get();

        try {
            List<String> codesToLoad = state.getCodesToLoad();
            Map<String, T> loaded = batchLoader.apply(codesToLoad);

            state.forEachPageToLoad((pageId, codes) -> {
                List<T> pageData = codes.stream()
                        .map(loaded::get)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                // 세대가 바뀌었으면 이 값은 틀린 게 아니라 낡은 것이다. 호출자와 대기자에게는
                // 돌려주되 캐시에는 남기지 않는다. TTL 은 이 값이 얼마나 오래 남느냐를 정할 뿐
                // 들어가는 것 자체를 막지 못한다 — 꺼져 있으면 다음 rebuild 까지, 켜져 있으면 TTL 만큼.
                if(gen == generation.get()) putCache(pageId, pageData);
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

    /** 인덱스가 준 페이지 순서대로 결과를 잇는다. 남이 로드 중인 페이지는 여기서만 기다린다. */
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

    /** 예열이 DB 에서 가져온 값을 넣는 통로. search 는 자기 loadPages 안에서 직접 넣는다. */
    public void putCache(int pageId, List<T> data) {
        pageCacheStore.put(pageId, data);
    }

    /**
     * 인덱스를 통째로 다시 만들고 캐시를 비운다. 예열은 하지 않는다 — 그건
     * AbstractSpatialCacheEngine.rebuild 가 이어서 한다.
     */
    public void rebuild(Consumer<SpatialRecordManager> loader) {
        long start = System.nanoTime();
        spatialRecordManager.rebuild(loader);    // 파일 재구축 + atomic rename
        clearCache();
        engineMetrics.incrementRebuildCount();
        engineMetrics.addRebuildMs((System.nanoTime() - start) / 1_000_000);
    }

    /**
     * 캐시를 비운다. rebuild 가 부르고, TTL 없이 배치 주기로 비우는 서비스가 직접 부르기도 한다.
     * 어느 경로든 비우기와 세대 교체는 한 쌍이라 여기 한 곳에서만 한다.
     */
    public void clearCache() {
        pageCacheStore.clearCache();
        // clearCache 뒤에 올린다. 앞이면 "세대는 새것인데 캐시는 옛 값" 인 창이 생긴다.
        // 뒤면 그 창에 들어온 put 은 곧 비워지므로 무해하다.
        generation.incrementAndGet();
    }

    /**
     * 예열 대상 — 접근 횟수 상위 pageId 와 각각의 코드 목록. 인기 내림차순이고 순서가 계약이다.
     *
     * 몇 개를 줄지는 정책이 정한다. warmupSize 가 상한이고, maxSize 가 켜져 있으면 그 이하로
     * 한 번 더 자른다 — 캐시에 못 들어갈 것을 DB 에서 가져올 이유가 없다.
     */
    public Map<Integer, List<String>> getWarmupTargets() {
        CachePolicy policy = pageCacheStore.getPolicy();
        int wanted = policy.isWarmupAll() ? Integer.MAX_VALUE : policy.getWarmupSize();
        int limit = policy.isMaxSizeEnabled() ? Math.min(wanted, policy.getMaxSize()) : wanted;
        return warmupStore.getTopPageIds(limit).stream()
                .collect(Collectors.toMap(
                        pageId -> pageId,
                        spatialRecordManager::getAllCodesByPageId,
                        (a, b) -> a,  // 병합 함수 — 키 중복은 없지만 4-인자 시그니처가 요구
                        LinkedHashMap::new  // 인기 순서 유지
                ));
    }

    public int getWarmupChunkSize() {
        return pageCacheStore.getPolicy().getWarmupChunkSize();
    }

    /** 접근 횟수를 파일로. 종료 시 한 번 — 다음 기동의 예열 근거가 된다. */
    public void saveWarmup() {
        warmupStore.saveHitCounts();
    }

    public void recordWarmupFailure() { engineMetrics.incrementWarmupFailureCount(); }

    public MetricsSnapshot getMetrics() {
        return engineMetrics.snapshot(
                (int) pageCacheStore.getCacheSize(),
                spatialRecordManager.getDirtyPageCount(),
                spatialRecordManager.getUsedOverflowPageCount(),
                spatialRecordManager.getUsedPageCount()
        );
    }

    public long getCacheSize() {
        return pageCacheStore.getCacheSize();
    }

    /**
     * 존재 + 미만료 확인. 관측용이라 운영 경로는 쓰지 않는다.
     * access-order 에서는 이 호출도 접근으로 세어 LRU 순서를 바꾼다 — 축출 순서를 확인하는
     * 테스트에서는 이 메서드 대신 getCacheSize 를 쓸 것.
     */
    public boolean isCached(int pageId) {
        return  pageCacheStore.isCached(pageId);
    }

    /** dirty 페이지 flush + 파일 닫기. 접근 횟수 저장은 하지 않는다 — 그건 saveWarmup 이다. */
    public void close(){
        spatialRecordManager.close();
    }
}
