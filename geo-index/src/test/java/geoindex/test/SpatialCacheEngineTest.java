package geoindex.test;

import geoindex.api.SpatialRecordManager;
import geoindex.api.SpatialCacheEngine;
import geoindex.buffer.CacheManager;
import geoindex.cache.CachePolicy;
import geoindex.cache.WarmupStore;
import geoindex.index.GeoHashIndex;
import geoindex.metric.EngineMetrics;
import geoindex.metric.MetricsSnapshot;
import geoindex.storage.DiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SpatialCacheEngineTest {

    static final String TEST_FILE = "test_cache_engine.db";
    static final String WARMUP_FILE = "test_cache_engine.store";
    WarmupStore warmupStore;
    EngineMetrics metrics;
    DiskManager diskManager;
    CacheManager cacheManager;
    GeoHashIndex geoHashIndex;
    SpatialRecordManager spatialRecordManager;
    SpatialCacheEngine<String> engine;

    /**
     * loader 호출 여부와 받은 코드를 기록한다. 판정만 돌려주던 3-arg search 가 사라진 뒤로
     * HIT/MISS 는 "loader 가 불렸나" 로만 관측한다 — 운영 경로와 같은 창이다.
     */
    static class RecordingLoader implements Function<List<String>, Map<String, String>> {
        int calls = 0;
        final List<String> received = new java.util.ArrayList<>();
        @Override public Map<String, String> apply(List<String> codes) {
            calls++;
            received.addAll(codes);
            Map<String, String> m = new HashMap<>();
            for (String c : codes) m.put(c, "v-" + c);
            return m;
        }
    }

    @BeforeEach
    void setup() {
        metrics = new EngineMetrics();
        diskManager = new DiskManager(TEST_FILE, metrics);
        cacheManager = new CacheManager(diskManager, metrics);
        geoHashIndex = new GeoHashIndex();
        spatialRecordManager = new SpatialRecordManager(cacheManager, geoHashIndex, metrics);
        warmupStore = new WarmupStore(Path.of(WARMUP_FILE));
        engine = new SpatialCacheEngine<>(spatialRecordManager, CachePolicy.DEFAULT, metrics, warmupStore);
    }

    @AfterEach
    void cleanup() throws Exception {
        cacheManager.close();
        Files.deleteIfExists(Path.of(TEST_FILE));
        Files.deleteIfExists(Path.of(TEST_FILE + ".new"));
        Files.deleteIfExists(Path.of(WARMUP_FILE));
    }

    // -------------------------------------------------------------------------
    // HIT / MISS 기본
    // -------------------------------------------------------------------------

    @Test
    void 첫요청_MISS() {
        spatialRecordManager.put(37.4979, 127.0276, "B0001".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        RecordingLoader loader = new RecordingLoader();
        engine.search(37.4979, 127.0276, 5.0, loader);

        assertEquals(1, loader.calls, "빈 캐시에서 첫 요청은 MISS — loader 가 불린다");
        assertTrue(loader.received.contains("B0001"));
    }

    @Test
    void put후_HIT() {
        spatialRecordManager.put(37.4979, 127.0276, "B0001".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        // 첫 요청 → MISS → search 가 loader 결과를 캐시에 넣는다
        RecordingLoader loader = new RecordingLoader();
        List<String> first = engine.search(37.4979, 127.0276, 5.0, loader);
        assertEquals(1, loader.calls);
        assertEquals(List.of("v-B0001"), first);

        // 두 번째 요청 → HIT → loader 가 안 불린다
        List<String> second = engine.search(37.4979, 127.0276, 5.0, loader);
        assertEquals(1, loader.calls, "HIT 이면 loader 가 불리지 않는다");
        assertEquals(first, second);
    }

    @Test
    void MISS_codes_포함() {
        spatialRecordManager.put(37.4979, 127.0276, "B0001".getBytes());
        spatialRecordManager.put(37.4985, 127.0280, "B0002".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        RecordingLoader loader = new RecordingLoader();
        engine.search(37.4979, 127.0276, 5.0, loader);

        assertTrue(loader.received.contains("B0001"), "MISS 페이지의 코드가 loader 에 전달돼야 한다");
        assertTrue(loader.received.contains("B0002"));
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
        SpatialCacheEngine<String> ttlEngine = new SpatialCacheEngine<>(srm, shortTtl, metrics, warmupStore);

        srm.put(37.4979, 127.0276, "B0001".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        // put → HIT
        int pageId = geoHashIndex.toPageId(37.4979, 127.0276);
        ttlEngine.putCache(pageId, List.of("B0001"));
        assertTrue(ttlEngine.isCached(pageId));

        // TTL 만료 대기
        Thread.sleep(150);

        // 만료 후 → MISS → loader 가 불린다
        RecordingLoader loader = new RecordingLoader();
        ttlEngine.search(37.4979, 127.0276, 5.0, loader);
        assertEquals(1, loader.calls, "TTL 만료 후 MISS여야 한다");
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

        RecordingLoader loader = new RecordingLoader();
        engine.search(37.4979, 127.0276, 5.0, loader);
        assertEquals(1, loader.calls, "clearCache 후 전부 MISS여야 한다");
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

        // JVM 캐시 비워짐 → MISS → loader 가 새 인덱스의 코드를 받는다
        RecordingLoader loader = new RecordingLoader();
        engine.search(37.4979, 127.0276, 5.0, loader);
        assertEquals(1, loader.calls, "rebuild 후 JVM 캐시 비워져야 한다");
        assertTrue(loader.received.contains("NEW_001") && loader.received.contains("NEW_002"),
                "새 데이터가 파일에 있어야 한다");
        assertFalse(loader.received.contains("OLD_001"), "기존 데이터는 없어야 한다");
    }

    // -------------------------------------------------------------------------
    // maxSize evict
    // -------------------------------------------------------------------------

    @Test
    void maxSize_초과시_evict() {
        CachePolicy limitedPolicy = CachePolicy.builder().maxSize(2).build();
        SpatialRecordManager srm = new SpatialRecordManager(cacheManager, geoHashIndex, metrics);
        SpatialCacheEngine<String> limitedEngine = new SpatialCacheEngine<>(srm, limitedPolicy, metrics, warmupStore);

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

        // MISS → HIT 흐름
        RecordingLoader loader = new RecordingLoader();
        engine.search(37.4979, 127.0276, 5.0, loader);   // MISS
        engine.search(37.4979, 127.0276, 5.0, loader);   // HIT

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
            "usedPageCount": %d 
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
                metricsSnapshot.pageReadCount, metricsSnapshot.pageWriteCount, metricsSnapshot.usedPageCount,
                metricsSnapshot.flushCount, metricsSnapshot.flushedPages, metricsSnapshot.dirtyPages,
                metricsSnapshot.overflowPageUsed
        );
    }
    // -------------------------------------------------------------------------
    // search 의 기본 계약 — 미스로 로드한 데이터가 결과에 들어 있어야 한다
    // 기존 테스트는 loader 호출 횟수만 셌다. 그래서 결과가 통째로 빠져도 통과했다.
    // -------------------------------------------------------------------------

    @Test
    void MISS로_로드한_데이터가_결과에_들어있다() {
        spatialRecordManager.put(37.4979, 127.0276, "B0001".getBytes());
        spatialRecordManager.put(37.4979, 127.0276, "B0002".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        List<String> result = engine.search(37.4979, 127.0276, 1.0, codes -> {
            Map<String, String> m = new HashMap<>();
            for (String c : codes) m.put(c, "v-" + c);
            return m;
        });

        assertEquals(2, result.size(), "미스로 로드한 페이지의 값이 결과에서 빠지면 안 된다");
        assertTrue(result.contains("v-B0001"));
        assertTrue(result.contains("v-B0002"));
    }

    // -------------------------------------------------------------------------
    // loader 가 Error 를 던지면 — catch(Exception) 을 건너뛴다.
    // 승자 스레드는 Error 로 죽지만, 그 future 를 쥔 대기 스레드는 finally 가 풀어줘야 한다.
    // -------------------------------------------------------------------------

    @Test
    void loader가_Error를_던져도_대기_스레드는_깨어난다() throws Exception {
        spatialRecordManager.put(37.4979, 127.0276, "B0001".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        CountDownLatch winnerInsideLoader = new CountDownLatch(1);   // 승자가 loader 안에 들어왔다
        CountDownLatch releaseWinner      = new CountDownLatch(1);   // 승자를 놓아준다 → Error 를 던진다
        AtomicBoolean waiterLoaderCalled  = new AtomicBoolean(false);
        AtomicReference<Throwable> waiterOutcome = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> winner = pool.submit(() -> engine.search(37.4979, 127.0276, 1.0, codes -> {
                winnerInsideLoader.countDown();
                try { releaseWinner.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                throw new StackOverflowError("simulated");             // Exception 이 아니다
            }));
            winnerInsideLoader.await(3, TimeUnit.SECONDS);

            Future<?> waiter = pool.submit(() -> {
                try {
                    engine.search(37.4979, 127.0276, 1.0, codes -> {
                        waiterLoaderCalled.set(true);                  // 여기 오면 안 된다 — 승자의 future 를 기다려야 한다
                        return Map.of();
                    });
                } catch (Throwable t) {
                    waiterOutcome.set(t);
                }
            });
            Thread.sleep(200);                                         // 대기자가 join() 에 도달할 시간
            releaseWinner.countDown();

            assertThrows(Exception.class, () -> winner.get(3, TimeUnit.SECONDS), "승자는 Error 로 끝난다");
            waiter.get(3, TimeUnit.SECONDS);                           // 여기서 TimeoutException 이면 영구 대기다

            assertFalse(waiterLoaderCalled.get(), "대기자는 직접 로드하지 않고 승자의 future 를 기다렸어야 한다");
            assertNotNull(waiterOutcome.get(), "대기자는 예외로 깨어나야 한다 — 빈 결과로 넘어가면 안 된다");
        } finally {
            releaseWinner.countDown();
            pool.shutdownNow();
        }
    }

    // -------------------------------------------------------------------------
    // rebuild 가 로딩 중간에 끼면 — 비우기 전에 시작된 로딩이 비운 뒤에 도착한다.
    // 결과는 돌려주되(틀린 게 아니라 낡은 것) 캐시에는 남기면 안 된다.
    // -------------------------------------------------------------------------

    @Test
    void rebuild가_로딩_중간에_끼면_결과는_오되_캐시에는_남지_않는다() throws Exception {
        spatialRecordManager.put(37.4979, 127.0276, "B0001".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();
        int pageId = geoHashIndex.toPageId(37.4979, 127.0276);

        CountDownLatch insideLoader = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<List<String>> inFlight = pool.submit(() -> engine.search(37.4979, 127.0276, 1.0, codes -> {
                insideLoader.countDown();                                   // DB 조회 시작
                try { releaseLoader.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                Map<String, String> m = new HashMap<>();
                for (String c : codes) m.put(c, "v-" + c);
                return m;
            }));
            insideLoader.await(3, TimeUnit.SECONDS);

            engine.rebuild(srm -> srm.put(37.4979, 127.0276, "B0001".getBytes()));   // 로딩 도중 비운다
            assertFalse(engine.isCached(pageId), "rebuild 직후 캐시는 비어 있다");

            releaseLoader.countDown();                                      // 이제야 DB 결과가 도착한다
            List<String> result = inFlight.get(3, TimeUnit.SECONDS);

            assertEquals(List.of("v-B0001"), result, "낡았어도 결과는 돌려준다");
            assertFalse(engine.isCached(pageId), "비운 뒤 도착한 로딩은 캐시에 남으면 안 된다");
        } finally {
            releaseLoader.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void loader_1번_호출_검증() throws InterruptedException {
        spatialRecordManager.put( 33.4996, 126.5312 , "B0001".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        AtomicInteger loaderCallCount = new AtomicInteger(0);
        int treadCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(treadCount);
        CountDownLatch countDownLatch = new CountDownLatch(treadCount);
        for (int i = 0; i < treadCount; i++) {
            executorService.submit(() -> {
                engine.search(33.4996, 126.5312, 5.0, codes -> {
                    loaderCallCount.incrementAndGet();
                    return Map.of("H001", "data");
                });
                countDownLatch.countDown();
            });
        }
        countDownLatch.await();
        executorService.shutdown();

        assertEquals(1, loaderCallCount.get(), "100 스레드가 같은 페이지를 미스해도 loader 는 한 번 — pendingLoads");
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
            return Map.of("data1", "data2");
        });

        assertEquals(0, loaderCallCount.get());
    }

}