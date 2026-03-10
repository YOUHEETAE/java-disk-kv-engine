package geoindex.cache;

import geoindex.api.PageResult;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * pageId 단위 JVM 캐시 엔진
 *
 * 책임:
 *   - pageId 단위 ConcurrentHashMap 캐시 보유
 *   - getOrMiss(): HIT/MISS 판단 (SpatialRecordManager.search()가 호출)
 *   - put(): MISS 후 DB 조회 결과를 JVM에 저장
 *   - clearCache(): JVM 캐시 초기화
 *   - TTL 만료 체크, maxSize 초과 시 evict
 *
 * 책임 아님:
 *   - 파일 검색 → SpatialRecordManager
 *   - pageId 변환 → SpatialIndex
 *   - DB 조회 → Spring
 *   - TTL 값 결정 → Spring Config
 *
 * 의존성:
 *   SpatialRecordManager → SpatialCacheEngine (단방향)
 *   SpatialCacheEngine → SpatialRecordManager 없음 (순환 참조 없음)
 */
public class SpatialCacheEngine<T> {

    private final CachePolicy policy;
    private volatile ConcurrentHashMap<Integer, CacheEntry<T>> pageCache;

    public SpatialCacheEngine() {
        this(CachePolicy.DEFAULT);
    }

    public SpatialCacheEngine(CachePolicy policy) {
        this.policy = policy;
        this.pageCache = new ConcurrentHashMap<>();
    }

    // -------------------------------------------------------------------------
    // HIT/MISS 판단 — SpatialRecordManager.search()가 호출
    // -------------------------------------------------------------------------

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
    public void put(int pageId, List<T> data) {
        if (policy.isMaxSizeEnabled() && pageCache.size() >= policy.getMaxSize()) {
            evictOne();
        }

        pageCache.compute(pageId, (k, existing) -> {
            List<T> list = (existing != null && !existing.isExpired())
                    ? new ArrayList<>(existing.getData())
                    : new ArrayList<>();
            list.addAll(data);

            return policy.isTtlEnabled()
                    ? CacheEntry.of(list, Instant.now().plus(policy.getTtl()))
                    : CacheEntry.of(list);
        });
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

    public long getCacheSize() {
        return pageCache.size();
    }

    public boolean isCached(int pageId) {
        CacheEntry<T> entry = pageCache.get(pageId);
        return entry != null && !entry.isExpired();
    }

    public CachePolicy getPolicy() {
        return policy;
    }

    private void evictOne() {
        Integer victim = pageCache.keys().nextElement();
        if (victim != null) {
            pageCache.remove(victim);
        }
    }
}