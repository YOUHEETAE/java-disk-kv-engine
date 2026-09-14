package geoindex.api;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

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
