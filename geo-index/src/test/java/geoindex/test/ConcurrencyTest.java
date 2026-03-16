package geoindex.concurrency;

import geoindex.api.SpatialRecordManager;
import geoindex.buffer.CacheManager;
import geoindex.index.GeoHashIndex;
import geoindex.storage.DiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class ConcurrencyTest {

    static final String TEST_DB = "concurrency_test.db";
    DiskManager diskManager;
    CacheManager cacheManager;
    SpatialRecordManager srm;
    GeoHashIndex geoHashIndex;

    static final double LAT = 37.5233551;
    static final double LNG = 127.0286669;
    static final int THREAD_COUNT = 500;

    @BeforeEach
    void setup() {
        diskManager = new DiskManager(TEST_DB);
        cacheManager = new CacheManager(diskManager);
        geoHashIndex = new GeoHashIndex();
        srm = new SpatialRecordManager(cacheManager, geoHashIndex);
    }

    @AfterEach
    void cleanup() throws Exception {
        cacheManager.close();
        Files.deleteIfExists(Path.of(TEST_DB));
    }

    // -------------------------------------------------------------------------
    // 테스트 1: 단일 pageId에 overflow 발생 후 동시 읽기
    // -------------------------------------------------------------------------

    @Test
    void 동시읽기_overflow_일관성() throws InterruptedException {
        // overflow 발생하도록 충분히 삽입 (4096바이트 페이지 꽉 채움)
        int insertCount = 500;
        for (int i = 0; i < insertCount; i++) {
            srm.put(LAT, LNG, String.format("HOSPITAL_CODE_%04d", i).getBytes());
        }
        cacheManager.flush();
        cacheManager.clearCache();

        int pageId = geoHashIndex.toPageId(LAT, LNG);
        int expectedCount = srm.getAllCodesByPageId(pageId).size();
        System.out.println("예상 코드 수: " + expectedCount);
        assertTrue(expectedCount > 0, "데이터가 있어야 한다");

        // 500 스레드 동시 읽기
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicInteger failCount = new AtomicInteger(0);
        ConcurrentLinkedQueue<Integer> failedCounts = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // 모든 스레드 동시 출발
                    List<String> codes = srm.getAllCodesByPageId(pageId);
                    if (codes.size() != expectedCount) {
                        failed.set(true);
                        failCount.incrementAndGet();
                        failedCounts.add(codes.size());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // 동시 출발
        doneLatch.await();
        executor.shutdown();

        System.out.println("실패 스레드 수: " + failCount.get() + " / " + THREAD_COUNT);
        System.out.println("실패한 코드 수 목록: " + failedCounts);
        assertFalse(failed.get(), "동시 읽기에서 코드 수 불일치 발생");
    }

    // -------------------------------------------------------------------------
    // 테스트 2: 동시 write + read
    // -------------------------------------------------------------------------

    @Test
    void 동시쓰기읽기_일관성() throws InterruptedException {
        // 기본 데이터 삽입
        int initialCount = 200;
        for (int i = 0; i < initialCount; i++) {
            srm.put(LAT, LNG, String.format("INIT_CODE_%04d", i).getBytes());
        }
        cacheManager.flush();
        cacheManager.clearCache();

        int pageId = geoHashIndex.toPageId(LAT, LNG);
        int expectedCount = srm.getAllCodesByPageId(pageId).size();
        System.out.println("초기 코드 수: " + expectedCount);

        // 250 read 스레드 + 250 write 스레드 동시 실행
        int readThreads = 250;
        int writeThreads = 250;
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicInteger failCount = new AtomicInteger(0);
        ConcurrentLinkedQueue<Integer> failedCounts = new ConcurrentLinkedQueue<>();

        // read 스레드
        for (int i = 0; i < readThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    List<String> codes = srm.getAllCodesByPageId(pageId);
                    // write 중이어도 최소한 초기 데이터는 있어야 함
                    if (codes.size() < expectedCount) {
                        failed.set(true);
                        failCount.incrementAndGet();
                        failedCounts.add(codes.size());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // write 스레드
        for (int i = 0; i < writeThreads; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    srm.put(LAT, LNG, String.format("NEW_CODE_%04d", idx).getBytes());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        System.out.println("실패 스레드 수: " + failCount.get() + " / " + readThreads);
        System.out.println("실패한 코드 수 목록: " + failedCounts);
        assertFalse(failed.get(), "동시 쓰기/읽기에서 데이터 누락 발생");
    }

    // -------------------------------------------------------------------------
    // 테스트 3: 반경 검색 동시 읽기
    // -------------------------------------------------------------------------

    @Test
    void 동시_반경검색_일관성() throws InterruptedException {
        // 반경 내 여러 좌표에 데이터 삽입
        double[][] locations = {
                {37.5233551, 127.0286669},
                {37.5240000, 127.0290000},
                {37.5250000, 127.0300000},
                {37.5200000, 127.0250000},
                {37.5180000, 127.0230000},
        };

        int perLocation = 100;
        for (double[] loc : locations) {
            for (int i = 0; i < perLocation; i++) {
                srm.put(loc[0], loc[1],
                        String.format("CODE_%s_%04d", loc[0], i).getBytes());
            }
        }
        cacheManager.flush();
        cacheManager.clearCache();

        // 단일 스레드 기준값
        List<String> expected = srm.searchRadiusCodes(LAT, LNG, 5.0);
        int expectedCount = expected.size();
        System.out.println("예상 검색 결과: " + expectedCount + "건");

        // 500 스레드 동시 반경 검색
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
        AtomicBoolean failed = new AtomicBoolean(false);
        AtomicInteger failCount = new AtomicInteger(0);
        ConcurrentLinkedQueue<Integer> failedCounts = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    List<String> codes = srm.searchRadiusCodes(LAT, LNG, 5.0);
                    if (codes.size() != expectedCount) {
                        failed.set(true);
                        failCount.incrementAndGet();
                        failedCounts.add(codes.size());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        System.out.println("실패 스레드 수: " + failCount.get() + " / " + THREAD_COUNT);
        System.out.println("실패한 코드 수 목록: " + failedCounts);
        assertFalse(failed.get(), "동시 반경 검색에서 결과 불일치 발생");
    }
}