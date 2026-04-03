package geoindex.test;

import geoindex.api.PageResult;
import geoindex.api.SpatialRecordManager;
import geoindex.api.SpatialCacheEngine;
import geoindex.buffer.CacheManager;
import geoindex.cache.CachePolicy;
import geoindex.index.GeoHashIndex;
import geoindex.metric.EngineMetrics;
import geoindex.metric.MetricsSnapshot;
import geoindex.storage.DiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SpatialCacheEngineTest {

    static final String TEST_FILE = "test_cache_engine.db";
    EngineMetrics metrics;
    DiskManager diskManager;
    CacheManager cacheManager;
    GeoHashIndex geoHashIndex;
    SpatialRecordManager spatialRecordManager;
    SpatialCacheEngine<String> engine;

    @BeforeEach
    void setup() {
        metrics = new EngineMetrics();
        diskManager = new DiskManager(TEST_FILE, metrics);
        cacheManager = new CacheManager(diskManager, metrics);
        geoHashIndex = new GeoHashIndex();
        spatialRecordManager = new SpatialRecordManager(cacheManager, geoHashIndex, metrics);
        engine = new SpatialCacheEngine<>(spatialRecordManager, metrics);
    }

    @AfterEach
    void cleanup() throws Exception {
        cacheManager.close();
        Files.deleteIfExists(Path.of(TEST_FILE));
        Files.deleteIfExists(Path.of(TEST_FILE + ".new"));
    }

    // -------------------------------------------------------------------------
    // HIT / MISS 기본
    // -------------------------------------------------------------------------

    @Test
    void 첫요청_MISS() {
        spatialRecordManager.put(37.4979, 127.0276, "B0001".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        List<PageResult<String>> results = engine.search(37.4979, 127.0276, 5.0);

        assertTrue(results.stream().anyMatch(r -> !r.isHit()));
        System.out.println("MISS pageId 수: " + results.stream().filter(r -> !r.isHit()).count());
    }

    @Test
    void put후_HIT() {
        spatialRecordManager.put(37.4979, 127.0276, "B0001".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        // 첫 요청 → MISS
        List<PageResult<String>> first = engine.search(37.4979, 127.0276, 5.0);
        assertTrue(first.stream().anyMatch(r -> !r.isHit()));

        // MISS → pageId 단위로 JVM에 저장
        first.stream().filter(r -> !r.isHit()).forEach(r ->
                engine.putCache(r.getPageId(), List.of("B0001"))
        );

        // 두 번째 요청 → HIT
        List<PageResult<String>> second = engine.search(37.4979, 127.0276, 5.0);
        assertTrue(second.stream().anyMatch(PageResult::isHit));
        System.out.println("HIT pageId 수: " + second.stream().filter(PageResult::isHit).count());
    }

    @Test
    void MISS_codes_포함() {
        spatialRecordManager.put(37.4979, 127.0276, "B0001".getBytes());
        spatialRecordManager.put(37.4985, 127.0280, "B0002".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        List<PageResult<String>> results = engine.search(37.4979, 127.0276, 5.0);

        results.stream().filter(r -> !r.isHit()).forEach(r -> {
            assertNotNull(r.getCodes());
            assertFalse(r.getCodes().isEmpty());
            System.out.println("MISS pageId: " + r.getPageId() + " codes: " + r.getCodes());
        });
    }

    // -------------------------------------------------------------------------
    // TTL
    // -------------------------------------------------------------------------

    @Test
    void TTL_만료후_MISS() throws Exception {
        CachePolicy shortTtl = CachePolicy.builder()
                .ttl(Duration.ofMillis(100))
                .build();
        SpatialRecordManager srm = new SpatialRecordManager(cacheManager, geoHashIndex, metrics);
        SpatialCacheEngine<String> ttlEngine = new SpatialCacheEngine<>(srm, shortTtl, metrics);

        srm.put(37.4979, 127.0276, "B0001".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        // put → HIT
        int pageId = geoHashIndex.toPageId(37.4979, 127.0276);
        ttlEngine.putCache(pageId, List.of("B0001"));
        assertTrue(ttlEngine.isCached(pageId));

        // TTL 만료 대기
        Thread.sleep(150);

        // 만료 후 → MISS
        List<PageResult<String>> after = ttlEngine.search(37.4979, 127.0276, 5.0);
        assertTrue(after.stream().anyMatch(r -> !r.isHit()), "TTL 만료 후 MISS여야 한다");
        System.out.println("TTL 만료 후 MISS 확인 ✅");
    }

    @Test
    void TTL_DISABLE_만료없음() throws Exception {
        int pageId = geoHashIndex.toPageId(37.4979, 127.0276);
        engine.putCache(pageId, List.of("B0001"));

        Thread.sleep(100);

        assertTrue(engine.isCached(pageId), "TTL_DISABLE → 만료 없음");
        System.out.println("TTL_DISABLE → 만료 없음 확인 ✅");
    }

    // -------------------------------------------------------------------------
    // clearCache
    // -------------------------------------------------------------------------

    @Test
    void clearCache_후_전체_MISS() {
        spatialRecordManager.put(37.4979, 127.0276, "B0001".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        int pageId = geoHashIndex.toPageId(37.4979, 127.0276);
        engine.putCache(pageId, List.of("B0001"));
        assertTrue(engine.getCacheSize() > 0);

        engine.clearCache();
        assertEquals(0, engine.getCacheSize());

        List<PageResult<String>> results = engine.search(37.4979, 127.0276, 5.0);
        assertTrue(results.stream().noneMatch(PageResult::isHit), "clearCache 후 전부 MISS여야 한다");
        System.out.println("clearCache 후 전체 MISS 확인 ✅");
    }

    // -------------------------------------------------------------------------
    // rebuild
    // -------------------------------------------------------------------------

    @Test
    void rebuild_후_파일_새데이터_조회() {
        spatialRecordManager.put(37.4979, 127.0276, "OLD_001".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        int pageId = geoHashIndex.toPageId(37.4979, 127.0276);
        engine.putCache(pageId, List.of("OLD_001"));

        // rebuild: 새 데이터로 파일 교체 + JVM 캐시 초기화
        engine.rebuild(srm -> {
            srm.put(37.4979, 127.0276, "NEW_001".getBytes());
            srm.put(37.4979, 127.0276, "NEW_002".getBytes());
        });

        // JVM 캐시 비워짐 → MISS
        List<PageResult<String>> results = engine.search(37.4979, 127.0276, 5.0);
        assertTrue(results.stream().anyMatch(r -> !r.isHit()), "rebuild 후 JVM 캐시 비워져야 한다");

        // MISS codes에 새 데이터 포함
        results.stream().filter(r -> !r.isHit()).forEach(r -> {
            assertTrue(r.getCodes().contains("NEW_001") || r.getCodes().contains("NEW_002"),
                    "새 데이터가 파일에 있어야 한다");
            assertFalse(r.getCodes().contains("OLD_001"), "기존 데이터는 없어야 한다");
        });
        System.out.println("rebuild 후 새 데이터 파일 조회 확인 ✅");
    }

    // -------------------------------------------------------------------------
    // maxSize evict
    // -------------------------------------------------------------------------

    @Test
    void maxSize_초과시_evict() {
        CachePolicy limitedPolicy = CachePolicy.builder().maxSize(2).build();
        SpatialRecordManager srm = new SpatialRecordManager(cacheManager, geoHashIndex, metrics);
        SpatialCacheEngine<String> limitedEngine = new SpatialCacheEngine<>(srm, limitedPolicy, metrics);

        limitedEngine.putCache(geoHashIndex.toPageId(37.4979, 127.0276), List.of("A"));
        limitedEngine.putCache(geoHashIndex.toPageId(37.5665, 126.9780), List.of("B"));
        limitedEngine.putCache(geoHashIndex.toPageId(37.5133, 127.1001), List.of("C"));

        assertTrue(limitedEngine.getCacheSize() <= 2,
                "maxSize 초과 시 evict되어야 한다. 현재 size: " + limitedEngine.getCacheSize());
        System.out.println("maxSize evict 후 size: " + limitedEngine.getCacheSize() + " ✅");
    }

    // -------------------------------------------------------------------------
    // CachePolicy
    // -------------------------------------------------------------------------

    @Test
    void CachePolicy_DEFAULT_검증() {
        CachePolicy policy = CachePolicy.DEFAULT;
        assertFalse(policy.isTtlEnabled(), "DEFAULT는 TTL 비활성화");
        assertFalse(policy.isMaxSizeEnabled(), "DEFAULT는 크기 무제한");
        System.out.println("CachePolicy DEFAULT 확인 ✅");
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
        System.out.println("CachePolicy 설정값 확인 ✅");
    }

    @Test
    void 메트릭_출력() {
        // 검색 + 캐시 동작 수행
        spatialRecordManager.put(37.4979, 127.0276, "B0001".getBytes());
        spatialRecordManager.put(37.4985, 127.0280, "B0002".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        // MISS → putCache → HIT 흐름
        List<PageResult<String>> first = engine.search(37.4979, 127.0276, 5.0);
        first.stream().filter(r -> !r.isHit())
                .forEach(r -> engine.putCache(r.getPageId(), List.of("B0001", "B0002")));
        engine.search(37.4979, 127.0276, 5.0); // HIT

        // 메트릭 출력
        MetricsSnapshot metricsSnapshot = engine.getMetrics();
        System.out.printf("""
        === Engine Metrics ===
        {
          "index": {
            "queryCount": %d,
            "avgPageIds": %.1f
          },
          "cache": {
            "pageHit": %d,
            "pageMiss": %d,
            "pageHitRate": %.3f,
            "cacheSize": %d,
            "evictCount": %d
          },
          "disk": {
            "pageReadCount": %d,
            "pageWriteCount": %d
          },
          "storage": {
            "flushCount": %d,
            "flushedPages": %d,
            "dirtyPages": %d
            "overflowPageUsed": %d
          }
        }%n""",
                metricsSnapshot.queryCount, metricsSnapshot.avgPageIds,
                metricsSnapshot.pageHit, metricsSnapshot.pageMiss, metricsSnapshot.pageHitRate,
                metricsSnapshot.cacheSize, metricsSnapshot.evictCount,
                metricsSnapshot.pageReadCount, metricsSnapshot.pageWriteCount,
                metricsSnapshot.flushCount, metricsSnapshot.flushedPages, metricsSnapshot.dirtyPages,
                metricsSnapshot.overflowPageUsed
        );
    }
    @Test
    void loader_1번_호출_검증() throws InterruptedException {
        spatialRecordManager.put(37.4979, 127.0276, "B0001".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        AtomicInteger loaderCallCount = new AtomicInteger(0);
        int treadCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(treadCount);
        CountDownLatch countDownLatch = new CountDownLatch(treadCount);
        for (int i = 0; i < treadCount; i++) {
            executorService.submit(() -> {
                engine.search(37.4979, 127.0276, 5.0, codes -> {
                    loaderCallCount.incrementAndGet();
                    return List.of("data");
                });
                countDownLatch.countDown();
            });
        }
        countDownLatch.await();
        executorService.shutdown();

        assertEquals(1, loaderCallCount.get());
    }
    @Test
    void exception_스레드_전파() throws InterruptedException {
        spatialRecordManager.put(37.4979, 127.0276, "B0001".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();
        int treadCount = 10;
        ExecutorService executorService = Executors.newFixedThreadPool(treadCount);
        CountDownLatch countDownLatch = new CountDownLatch(treadCount);
        AtomicInteger exceptionCount = new AtomicInteger(0);
        for (int i = 0; i < treadCount; i++) {
            executorService.submit(() -> {
                try {
                    engine.search(37.4979, 127.0276, 5.0, codes -> {
                        throw new RuntimeException("loader 실패");
                    });
                } catch (Exception e) {
                    exceptionCount.incrementAndGet();
                } finally {
                    countDownLatch.countDown();
                }
            });
        }
        countDownLatch.await();
        executorService.shutdown();

        assertEquals(treadCount, exceptionCount.get(), "모든 스레드가 예외를 받아야 한다");
        System.out.println("\"exception 전파 확인 (deadlock 없음)\"");
    }
    @Test
    void 캐시HIT시_loader_미호출(){
        spatialRecordManager.put(37.4979, 127.0276, "B0001".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();
        engine.putCache(geoHashIndex.toPageId(37.4979, 127.0276) , List.of("data1", "data2"));
        AtomicInteger loaderCallCount = new AtomicInteger(0);
        engine.search(37.4979, 127.0276, 5.0, codes -> {
            loaderCallCount.incrementAndGet();
            return List.of("data1", "data2");
        });

        assertEquals(0, loaderCallCount.get());
    }

}