package geoindex.cache;

import geoindex.api.PageResult;
import geoindex.api.SpatialCache;
import geoindex.api.SpatialRecordManager;
import geoindex.index.SpatialIndex;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * pageId 단위 공간 캐시 엔진
 *
 * 책임:
 *   - GeoHash 알고리즘으로 좌표 → pageId 변환
 *   - pageId 단위 ConcurrentHashMap 캐시 보유
 *   - TTL 만료 체크 (CacheEntry.isExpired())
 *   - maxSize 초과 시 가장 오래된 항목 제거 (LRU 근사)
 *   - clearCache(): 배치 완료 시 Spring @Scheduled 호출
 *   - rebuild(): 엔진 파일 교체 + 캐시 초기화 + 데이터 재적재
 *
 * 책임 아님:
 *   - TTL 값 결정 → Spring Config (@Value 주입 → CachePolicy)
 *   - 배치 스케줄 → Spring @Scheduled
 *   - DB 조회 → Spring SpatialCacheService
 */
public class SpatialCacheEngine<T> implements SpatialCache<T> {

    private final SpatialRecordManager spatialRecordManager;
    private final SpatialIndex spatialIndex;
    private final Function<T, double[]> coordExtractor; // T → [lat, lng]
    private final CachePolicy policy;

    // volatile: rebuild() 시 새 Map으로 atomic 교체
    private volatile ConcurrentHashMap<Integer, CacheEntry<T>> pageCache;

    // -------------------------------------------------------------------------
    // 생성자
    // -------------------------------------------------------------------------

    public SpatialCacheEngine(
            SpatialRecordManager spatialRecordManager,
            SpatialIndex spatialIndex,
            Function<T, double[]> coordExtractor) {
        this(spatialRecordManager, spatialIndex, coordExtractor, CachePolicy.DEFAULT);
    }

    public SpatialCacheEngine(
            SpatialRecordManager spatialRecordManager,
            SpatialIndex spatialIndex,
            Function<T, double[]> coordExtractor,
            CachePolicy policy) {
        this.spatialRecordManager = spatialRecordManager;
        this.spatialIndex = spatialIndex;
        this.coordExtractor = coordExtractor;
        this.policy = policy;
        this.pageCache = new ConcurrentHashMap<>();
    }

    // -------------------------------------------------------------------------
    // SpatialCache<T> 구현
    // -------------------------------------------------------------------------

    @Override
    public List<PageResult<T>> search(double lat, double lng, double radiusKm) {
        Map<Integer, List<String>> codesByPageId =
                spatialRecordManager.searchRadiusCodesByPageId(lat, lng, radiusKm);

        List<PageResult<T>> results = new ArrayList<>();

        for (Map.Entry<Integer, List<String>> entry : codesByPageId.entrySet()) {
            int pageId = entry.getKey();
            List<String> codes = entry.getValue();

            CacheEntry<T> cached = pageCache.get(pageId);

            // HIT: 캐시에 있고 만료되지 않은 경우
            if (cached != null && !cached.isExpired()) {
                results.add(PageResult.hit(pageId, cached.getData()));
            } else {
                // 만료된 항목 제거
                if (cached != null) {
                    pageCache.remove(pageId);
                }
                results.add(PageResult.miss(pageId, codes));
            }
        }

        return results;
    }

    @Override
    public void put(List<T> data) {
        for (T item : data) {
            double[] coords = coordExtractor.apply(item);
            int pageId = spatialIndex.toPageId(coords[0], coords[1]);

            // maxSize 초과 시 임의 항목 제거
            if (policy.isMaxSizeEnabled() && pageCache.size() >= policy.getMaxSize()) {
                evictOne();
            }

            pageCache.compute(pageId, (k, existing) -> {
                // 기존 항목이 유효하면 이어서 추가, 만료됐으면 새로 시작
                List<T> list = (existing != null && !existing.isExpired())
                        ? new ArrayList<>(existing.getData())
                        : new ArrayList<>();
                list.add(item);

                return policy.isTtlEnabled()
                        ? CacheEntry.of(list, Instant.now().plus(policy.getTtl()))
                        : CacheEntry.of(list);
            });
        }
    }

    // -------------------------------------------------------------------------
    // 캐시 초기화
    // -------------------------------------------------------------------------

    /**
     * 캐시 전체 초기화
     *
     * 사용 시점:
     *   - 배치 업데이트 완료 후 Spring @Scheduled 호출
     *   - TTL_DISABLE 환경에서 수동 만료 역할
     */
    public void clearCache() {
        pageCache.clear();
    }

    // -------------------------------------------------------------------------
    // 엔진 재빌드
    // -------------------------------------------------------------------------

    /**
     * 엔진 재빌드 + 캐시 초기화 + 데이터 재적재
     *
     * Why Consumer<SpatialCache<T>>:
     *   엔진은 병원 데이터가 어디서 오는지 모른다.
     *   Spring이 loader를 통해 데이터를 주입하는 구조로
     *   엔진-Spring 의존성 분리를 유지한다.
     *
     * 사용 예 (Spring):
     *   engine.rebuild(e -> hospitalRepository.findAll()
     *       .forEach(h -> e.put(List.of(h))));
     *
     * 동작:
     *   1. 새 빈 캐시로 atomic 교체 (기존 요청은 old cache 계속 사용)
     *   2. loader로 새 데이터 적재
     *   3. 완료 후 새 캐시가 서비스됨
     */
    public void rebuild(Consumer<SpatialCache<T>> loader) {
        // 1. 새 캐시 준비
        ConcurrentHashMap<Integer, CacheEntry<T>> newCache = new ConcurrentHashMap<>();

        // 2. 임시로 새 캐시에 데이터 적재
        SpatialCacheEngine<T> temp = new SpatialCacheEngine<>(
                spatialRecordManager, spatialIndex, coordExtractor, policy);
        temp.pageCache = newCache;
        loader.accept(temp);

        // 3. atomic 교체 (volatile write)
        this.pageCache = newCache;
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

    /**
     * maxSize 초과 시 임의 항목 하나 제거
     *
     * Why 임의 제거:
     *   ConcurrentHashMap은 삽입 순서를 보장하지 않는다.
     *   엄밀한 LRU가 필요하면 LinkedHashMap + 동기화가 필요하지만
     *   현재 케이스(23MB, 무제한)에서는 maxSize 자체를 거의 사용하지 않으므로
     *   단순 임의 제거로 충분하다.
     */
    private void evictOne() {
        Integer victim = pageCache.keys().nextElement();
        if (victim != null) {
            pageCache.remove(victim);
        }
    }

    private CacheEntry<T> emptyEntry() {
        return CacheEntry.of(new ArrayList<>());
    }
}