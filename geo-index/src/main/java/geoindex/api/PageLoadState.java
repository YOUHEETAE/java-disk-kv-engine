package geoindex.api;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * search 한 번의 분류 상태. 각 pageId 는 셋 중 하나다.
 *   readyPages       손에 있다 — 캐시 HIT 이거나 내가 방금 로드했다. 조립 때 그대로 쓴다
 *   waitingFutures   남이 로드 중이다 — 조립 때 join 한다
 *   pagesToLoad      내가 로드할 것이다 — myFutures 가 그 약속. 로드가 끝나면 readyPages 로 옮긴다
 *
 * 요청 하나가 만들고 버리는 객체라 동기화가 없다. pendingLoads(엔진의 공유 맵)와는 다르다 —
 * 이 클래스는 pendingLoads 를 모르고, 거기서 빼는 것은 엔진의 일이다.
 */
class PageLoadState<T> {
    private final Map<Integer, List<T>> readyPages = new LinkedHashMap<>();
    private final Map<Integer, CompletableFuture<List<T>>> waitingFutures = new LinkedHashMap<>();
    private final Map<Integer, List<String>> pagesToLoad = new LinkedHashMap<>();
    private final Map<Integer, CompletableFuture<List<T>>> myFutures = new LinkedHashMap<>();

    public void putReadyPage (int pageId, List<T> pageData) {
        readyPages.put(pageId, pageData);
    }

    public boolean hasReadyPage (int pageId) {
        return readyPages.containsKey(pageId);
    }

    public List<T> getReadyPage (int pageId) {
        return readyPages.get(pageId);
    }

    public void addWaitingFuture (int pageId, CompletableFuture<List<T>> pendingLoad) {
        waitingFutures.put(pageId, pendingLoad);
    }

    public boolean hasWaitingFuture (int pageId) {
        return waitingFutures.containsKey(pageId);
    }

    public CompletableFuture<List<T>> getWaitingFuture (int pageId) {
        return waitingFutures.get(pageId);
    }

    public void addPageToLoad (int pageId, List<String> codes) {
        pagesToLoad.put(pageId, codes);
    }

    public boolean hasPageToLoad () {
        return !pagesToLoad.isEmpty();
    }

    public List<String> getCodesToLoad () {
        return pagesToLoad.values().stream()
                .flatMap(Collection::stream)
                .distinct()
                .toList();
    }

    public void forEachPageToLoad (BiConsumer<Integer, List<String>> consumer) {
        pagesToLoad.forEach(consumer);
    }

    public void registerMyFuture (int pageId, CompletableFuture<List<T>> future) {
        myFutures.put(pageId, future);
    }

    public CompletableFuture<List<T>> getMyFuture (int pageId) {
        return myFutures.get(pageId);
    }

    public void propagateFailure(Throwable e) {
        myFutures.values().forEach(future -> future.completeExceptionally(e));
    }

    public void forEachMyFuture (BiConsumer<Integer, CompletableFuture<List<T>>> consumer) {
        myFutures.forEach(consumer);
    }
}
