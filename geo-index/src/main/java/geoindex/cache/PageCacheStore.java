package geoindex.cache;

import geoindex.api.PageResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class PageCacheStore<T> {
    private final CachePolicy policy;
    private volatile ConcurrentHashMap<Integer, CacheEntry<T>> pageCache;

    public PageCacheStore(CachePolicy policy) {
        this.policy = policy;
        this.pageCache = new ConcurrentHashMap<>();
    }
    /**
     * pageId → HIT이면 캐시 데이터, MISS이면 codes 반환
     *
     * SpatialRecordManager.search()에서 pageId + codes를 넘겨주면
     * 캐시 엔진이 HIT/MISS 판단만 담당
     */
    public PageResult<T> getOrMiss(int pageId, List<String> codes) {
        CacheEntry<T> cached = pageCache.get(pageId);

        if (cached != null && !cached.isExpired()) {
            return PageResult.hit(pageId, cached.getData());
        }

        // 만료된 항목 제거
        if (cached != null) {
            pageCache.remove(pageId);
        }
        return PageResult.miss(pageId, codes);
    }

    // -------------------------------------------------------------------------
    // JVM 캐시 저장 — MISS 후 DB 조회 결과를 Spring이 호출
    // -------------------------------------------------------------------------

    /**
     * MISS 후 DB 조회 결과를 pageId 단위로 JVM에 저장
     */
    public synchronized void put(int pageId, List<T> data) {
        if (policy.isMaxSizeEnabled() && pageCache.size() >= policy.getMaxSize()) {
            evictOne();
        }

        pageCache.put(pageId,
                policy.isTtlEnabled()
                        ? CacheEntry.of(data, Instant.now().plus(policy.getTtl()))
                        : CacheEntry.of(data)
        );
    }

    // -------------------------------------------------------------------------
    // JVM 캐시 초기화
    // -------------------------------------------------------------------------

    public void clearCache() {
        pageCache = new ConcurrentHashMap<>();
    }

    // -------------------------------------------------------------------------
    // 유틸
    // -------------------------------------------------------------------------


    public boolean isCached(int pageId) {
        CacheEntry<T> entry = pageCache.get(pageId);
        return entry != null && !entry.isExpired();
    }

    public long getCacheSize() {
        return pageCache.size();
    }


    private void evictOne() {
        Integer victim = pageCache.keys().nextElement();
        if (victim != null) {
            pageCache.remove(victim);
        }
    }
    public CachePolicy getPolicy() {
        return policy;
    }
}
