package geoindex.cache;

import geoindex.api.PageResult;
import geoindex.metric.EngineMetrics;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * pageId 단위 JVM 캐시 — MariaDB 에서 가져온 객체 목록을 페이지 번호로 붙잡아 둔다.
 *
 * 동기화 규약
 *   pageCache 를 건드리는 모든 public 메서드는 인스턴스 모니터를 잡는다. 이름이 읽기처럼
 *   보여도 예외가 없다. access-order LinkedHashMap 은 get() 이 접근 순서 링크를 재배치하는
 *   구조 변경이고, clear() 는 테이블·head·tail 을 지우는 세 번의 쓰기다. 한쪽만 잡으면
 *   아무도 보호받지 못한다 — 상호 배제는 양쪽이 같은 모니터를 쥘 때만 성립한다.
 *
 * 잃어도 되는 사본이다
 *   원본은 MariaDB 에 있다. clearCache() 가 flush 없이 그냥 비우는 이유이고, 손상된 항목을
 *   예외 대신 MISS 로 처리해도 되는 이유다. Layer 1 의 CacheManager 가 dirty page 를
 *   유일한 사본으로 들고 있는 것과 정반대다.
 */
public class PageCacheStore<T> {
    private final CachePolicy policy;
    private final LinkedHashMap<Integer, CacheEntry<T>> pageCache;
    private final EngineMetrics engineMetrics;
    private final WarmupStore warmupStore;

    public PageCacheStore(CachePolicy policy, EngineMetrics engineMetrics,  WarmupStore warmupStore) {
        this.policy = policy;
        // 세 번째 인자가 access-order 다. 축출이 꺼져 있으면(UNLIMITED) 순서를 쓸 곳이 없는데
        // 매 조회마다 링크를 재배치하게 되므로, 정책이 켜졌을 때만 유지한다.
        this.pageCache = new LinkedHashMap<>(16, 0.75f, policy.isMaxSizeEnabled());
        this.engineMetrics = engineMetrics;
        this.warmupStore = warmupStore;
    }

    /**
     * pageId 로 캐시를 판정한다. HIT 이면 데이터를, MISS 이면 넘겨받은 codes 를 그대로 돌려준다.
     * DB 조회는 하지 않는다 — 그건 SpatialCacheEngine.search() 가 MISS 를 모아서 한다.
     *
     * recordAccess 가 HIT/MISS 판정보다 먼저 오는 이유:
     *   warmupStore 는 캐시 성능이 아니라 페이지 수요를 센다. 미스도 수요이므로 반드시
     *   세야 한다 — 히트만 세면 정작 예열이 필요한 페이지가 후보에서 영원히 빠진다.
     *   engineMetrics 의 hit/miss 카운터가 성능 쪽이고, 둘은 목적이 다르다.
     */
    public synchronized PageResult<T> getOrMiss(int pageId, List<String> codes) {
        CacheEntry<T> cached = pageCache.get(pageId);

        warmupStore.recordAccess(pageId);

        if (cached != null && !cached.isExpired()) {
            engineMetrics.incrementPageHit();
            return PageResult.hit(pageId, cached.getData());
        }

        if (cached != null) {
            pageCache.remove(pageId);
        }
        engineMetrics.incrementPageMiss();
        return PageResult.miss(pageId, codes);
    }

    /** 판정만 한다 — 메트릭도 접근 기록도 올리지 않는다. 같은 요청의 double-check 용. */
    public synchronized List<T> peekIfCached(int pageId) {
        CacheEntry<T> cached = pageCache.get(pageId);
        return (cached != null && !cached.isExpired()) ? cached.getData() : null;
    }

    /**
     * DB 조회 결과를 pageId 단위로 저장한다. 같은 pageId 면 교체다.
     *
     * List.copyOf 로 저장하는 이유:
     *   호출자의 참조를 그대로 넣으면 getData() 로 도로 나가서, 결과를 정렬하는 순간
     *   캐시가 바뀐다. copyOf 는 불변이라 밖에서 못 고치고, null 원소를 저장 시점에
     *   거부한다 — 읽는 쪽에서 터지는 것보다 넣는 쪽에서 터지는 편이 원인에 가깝다.
     */
    public synchronized void put(int pageId, List<T> data) {
        // 이미 있는 키는 교체라 크기가 늘지 않는다. 그런데도 축출하면 애먼 항목이 나가고
        // evict 카운터가 오른다. containsKey 는 get 과 달리 접근 순서를 건드리지 않는다 —
        // get(pageId) != null 로 바꾸면 이 줄이 LRU 순서를 흔든다.
        if (policy.isMaxSizeEnabled()
                && pageCache.size() >= policy.getMaxSize()
                && !pageCache.containsKey(pageId)) {
            evictOne();
        }

        pageCache.put(pageId,
                policy.isTtlEnabled()
                        ? CacheEntry.of(List.copyOf(data), Instant.now().plus(policy.getTtl()))
                        : CacheEntry.of(List.copyOf(data))
        );
    }

    /** access-order 에서는 첫 번째 키가 가장 오래 안 쓴 것이다. 정렬이나 탐색이 없다. */
    private void evictOne() {
        engineMetrics.incrementEvictCount();
        Integer victim = pageCache.keySet().iterator().next();
        pageCache.remove(victim);
    }

    /** rebuild 가 인덱스를 갈아끼운 뒤 부른다. 사본이라 flush 없이 버린다. */
    public synchronized void clearCache() {
        pageCache.clear();
    }

    public synchronized boolean isCached(int pageId) {
        CacheEntry<T> entry = pageCache.get(pageId);
        return entry != null && !entry.isExpired();
    }

    public synchronized long getCacheSize() {
        return pageCache.size();
    }

    public CachePolicy getPolicy() {
        return policy;
    }
}
