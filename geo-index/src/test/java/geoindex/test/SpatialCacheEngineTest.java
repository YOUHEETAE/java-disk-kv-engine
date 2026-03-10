package geoindex.test;

import geoindex.api.PageResult;
import geoindex.api.SpatialRecordManager;
import geoindex.buffer.CacheManager;
import geoindex.cache.CachePolicy;
import geoindex.cache.SpatialCacheEngine;
import geoindex.index.GeoHashIndex;
import geoindex.storage.DiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpatialCacheEngineTest {

    static final String TEST_FILE = "test_cache_engine.db";
    DiskManager diskManager;
    CacheManager cacheManager;
    SpatialRecordManager spatialRecordManager;
    GeoHashIndex geoHashIndex;
    SpatialCacheEngine<String> engine;

    // coordExtractor: "lat,lng,code" 형식
    static final java.util.function.Function<String, double[]> COORD = item -> {
        String[] parts = item.split(",");
        return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
    };

    @BeforeEach
    void setup() throws Exception {
        diskManager = new DiskManager(TEST_FILE);
        cacheManager = new CacheManager(diskManager);
        geoHashIndex = new GeoHashIndex();
        spatialRecordManager = new SpatialRecordManager(cacheManager, geoHashIndex);
        engine = new SpatialCacheEngine<>(spatialRecordManager, geoHashIndex, COORD);
    }

    @AfterEach
    void cleanup() throws Exception {
        cacheManager.close();
        Files.deleteIfExists(Path.of(TEST_FILE));
    }

    @Test
    void testSearch_첫요청_MISS() {
        spatialRecordManager.put(37.4979, 127.0276, "B0001".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        List<PageResult<String>> results = engine.search(37.4979, 127.0276, 5.0);

        // 첫 요청 → 전부 MISS
        assertTrue(results.stream().anyMatch(r -> !r.isHit()));
        System.out.println("MISS pageId 수: " + results.stream().filter(r -> !r.isHit()).count());
    }

    @Test
    void testSearch_put후_HIT() {
        spatialRecordManager.put(37.4979, 127.0276, "B0001".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        // 첫 요청 → MISS → put
        List<PageResult<String>> first = engine.search(37.4979, 127.0276, 5.0);
        List<String> missData = List.of("37.4979,127.0276,B0001");
        engine.put(missData);

        // 두 번째 요청 → HIT
        List<PageResult<String>> second = engine.search(37.4979, 127.0276, 5.0);
        assertTrue(second.stream().anyMatch(PageResult::isHit));
        System.out.println("HIT pageId 수: " + second.stream().filter(PageResult::isHit).count());
    }

    @Test
    void testPut_pageId별_분류() {
        // 강남 2개, 역삼 1개 put
        engine.put(List.of(
                "37.4979,127.0276,B0001",
                "37.4985,127.0280,B0002",
                "37.5100,127.0400,B0003"
        ));

        System.out.println("캐시된 pageId 수: " + engine.getCacheSize());
        assertTrue(engine.getCacheSize() >= 1);
    }

    @Test
    void testSearch_MISS_codes_포함() {
        spatialRecordManager.put(37.4979, 127.0276, "B0001".getBytes());
        spatialRecordManager.put(37.4985, 127.0280, "B0002".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        List<PageResult<String>> results = engine.search(37.4979, 127.0276, 5.0);

        // MISS pageResult는 codes를 가지고 있어야 함
        results.stream()
                .filter(r -> !r.isHit())
                .forEach(r -> {
                    assertNotNull(r.getCodes());
                    assertFalse(r.getCodes().isEmpty());
                    System.out.println("MISS pageId: " + r.getPageId() + " codes: " + r.getCodes());
                });
    }

    @Test
    void testIsCached() {
        spatialRecordManager.put(37.4979, 127.0276, "B0001".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        // put 전 → 캐싱 안됨
        List<PageResult<String>> first = engine.search(37.4979, 127.0276, 5.0);
        first.stream()
                .filter(r -> !r.isHit())
                .forEach(r -> assertFalse(engine.isCached(r.getPageId())));

        // put 후 → 캐싱됨
        engine.put(List.of("37.4979,127.0276,B0001"));
        first.stream()
                .filter(r -> !r.isHit())
                .forEach(r -> assertTrue(engine.isCached(r.getPageId())));
    }
    @Test
    void TTL_만료후_MISS처리() throws Exception {
        // TTL 100ms로 엔진 생성
        CachePolicy shortTtl = CachePolicy.builder()
                .ttl(Duration.ofMillis(100))
                .build();
        SpatialCacheEngine<String> ttlEngine = new SpatialCacheEngine<>(
                spatialRecordManager, geoHashIndex, COORD, shortTtl);

        spatialRecordManager.put(37.4979, 127.0276, "B0001".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        // put → HIT 확인
        ttlEngine.put(List.of("37.4979,127.0276,B0001"));
        List<PageResult<String>> before = ttlEngine.search(37.4979, 127.0276, 5.0);
        assertTrue(before.stream().anyMatch(PageResult::isHit));

        // TTL 만료 대기
        Thread.sleep(150);

        // 만료 후 → MISS
        List<PageResult<String>> after = ttlEngine.search(37.4979, 127.0276, 5.0);
        assertTrue(after.stream().anyMatch(r -> !r.isHit()),
                "TTL 만료 후 MISS여야 한다");
        System.out.println("TTL 만료 후 MISS 확인 ✅");
    }

    @Test
    void TTL_DISABLE_만료없음() throws Exception {
        // DEFAULT = TTL_DISABLE
        engine.put(List.of("37.4979,127.0276,B0001"));

        Thread.sleep(100);

        // TTL 없으므로 여전히 HIT
        assertTrue(engine.isCached(
                geoHashIndex.toPageId(37.4979, 127.0276)));
        System.out.println("TTL_DISABLE → 만료 없음 확인 ✅");
    }

    @Test
    void clearCache_후_전체_MISS() {
        spatialRecordManager.put(37.4979, 127.0276, "B0001".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        // 캐시 채우기
        engine.put(List.of("37.4979,127.0276,B0001"));
        assertTrue(engine.getCacheSize() > 0);

        // clearCache 후
        engine.clearCache();
        assertEquals(0, engine.getCacheSize());

        // 검색하면 전부 MISS
        List<PageResult<String>> results = engine.search(37.4979, 127.0276, 5.0);
        assertTrue(results.stream().noneMatch(PageResult::isHit),
                "clearCache 후 전부 MISS여야 한다");
        System.out.println("clearCache 후 전체 MISS 확인 ✅");
    }

    @Test
    void rebuild_후_새데이터_HIT() {
        spatialRecordManager.put(37.4979, 127.0276, "B0001".getBytes());
        spatialRecordManager.put(37.4979, 127.0276, "B0002".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        // 기존 데이터 캐시
        engine.put(List.of("37.4979,127.0276,OLD"));
        assertEquals(1, engine.getCacheSize());

        // rebuild: 새 데이터로 교체
        engine.rebuild(e -> e.put(List.of(
                "37.4979,127.0276,B0001",
                "37.4979,127.0276,B0002"
        )));

        // 새 데이터로 HIT
        List<PageResult<String>> results = engine.search(37.4979, 127.0276, 5.0);
        assertTrue(results.stream().anyMatch(PageResult::isHit));

        // 새 데이터에 B0001, B0002 포함 확인
        results.stream()
                .filter(PageResult::isHit)
                .forEach(r -> {
                    assertTrue(r.getCached().contains("37.4979,127.0276,B0001")
                            || r.getCached().contains("37.4979,127.0276,B0002"));
                });
        System.out.println("rebuild 후 새 데이터 HIT 확인 ✅");
    }

    @Test
    void maxSize_초과시_evict() {
        // maxSize=2로 엔진 생성
        CachePolicy limitedPolicy = CachePolicy.builder()
                .maxSize(2)
                .build();
        SpatialCacheEngine<String> limitedEngine = new SpatialCacheEngine<>(
                spatialRecordManager, geoHashIndex, COORD, limitedPolicy);

        // 서로 다른 pageId 3개 put
        limitedEngine.put(List.of(
                "37.4979,127.0276,A",  // 강남
                "37.5665,126.9780,B",  // 홍대
                "37.5133,127.1001,C"   // 잠실
        ));

        // maxSize=2 이하여야 함
        assertTrue(limitedEngine.getCacheSize() <= 2,
                "maxSize 초과 시 evict되어야 한다. 현재 size: " + limitedEngine.getCacheSize());
        System.out.println("maxSize 초과 evict 후 size: " + limitedEngine.getCacheSize() + " ✅");
    }

    @Test
    void CachePolicy_DEFAULT_검증() {
        CachePolicy policy = CachePolicy.DEFAULT;

        assertFalse(policy.isTtlEnabled(), "DEFAULT는 TTL 비활성화");
        assertFalse(policy.isMaxSizeEnabled(), "DEFAULT는 크기 무제한");
        System.out.println("CachePolicy: " + policy);
    }

    @Test
    void CachePolicy_설정값_검증() {
        CachePolicy policy = CachePolicy.builder()
                .ttl(Duration.ofDays(7))
                .maxSize(5000)
                .build();

        assertTrue(policy.isTtlEnabled());
        assertEquals(Duration.ofDays(7), policy.getTtl());
        assertTrue(policy.isMaxSizeEnabled());
        assertEquals(5000, policy.getMaxSize());
        System.out.println("CachePolicy: " + policy);
    }
}