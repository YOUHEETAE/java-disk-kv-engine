package geoindex.test;

import geoindex.api.SpatialRecordManager;
import geoindex.buffer.CacheManager;
import geoindex.index.GeoHashIndex;
import geoindex.metric.EngineMetrics;
import geoindex.storage.DiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SpatialRecordManagerTest {

    static final String TEST_FILE = "test_spatial.db";
    SpatialRecordManager manager;
    CacheManager cacheManager;
    DiskManager diskManager;

    @BeforeEach
    void setup() {
        EngineMetrics metrics = new EngineMetrics();
        diskManager = new DiskManager(TEST_FILE, metrics);
        cacheManager = new CacheManager(diskManager, metrics);
        manager = new SpatialRecordManager(cacheManager, new GeoHashIndex(), metrics);
    }

    @AfterEach
    void cleanup() throws Exception {
        cacheManager.close();
        Files.deleteIfExists(Path.of(TEST_FILE));
    }

    @Test
    void testSearchRadiusEmpty() {
        List<byte[]> results = manager.searchRadius(37.4979, 127.0276, 5.0);
        assertTrue(results.isEmpty());
    }

    @Test
    void testMultipleInserts() {
        for (int i = 0; i < 100; i++) {
            manager.put(37.4979 + i * 0.0001, 127.0276 + i * 0.0001, ("병원" + i).getBytes());
        }
        List<byte[]> results = manager.searchRadius(37.4979, 127.0276, 5.0);
        System.out.println("검색 결과 수: " + results.size());
        assertTrue(results.size() > 0);
    }
    @Test
    void testSearchRadiusCodesByPageId_기본동작() {
        // 강남 근처 병원 3개 삽입
        manager.put(37.4979, 127.0276, "B0001".getBytes());
        manager.put(37.4985, 127.0280, "B0002".getBytes());
        manager.put(37.4990, 127.0290, "B0003".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        Map<Integer, List<String>> result =
                manager.searchRadiusCodesByPageId(37.4979, 127.0276, 5.0);

        assertFalse(result.isEmpty());
        // 전체 codes 추출
        List<String> allCodes = result.values().stream()
                .flatMap(List::stream)
                .toList();

        assertTrue(allCodes.contains("B0001"));
        assertTrue(allCodes.contains("B0002"));
        assertTrue(allCodes.contains("B0003"));
    }

    @Test
    void testSearchRadiusCodesByPageId_pageId별로_묶임() {
        manager.put(37.4979, 127.0276, "B0001".getBytes());
        manager.put(37.4985, 127.0280, "B0002".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        Map<Integer, List<String>> result =
                manager.searchRadiusCodesByPageId(37.4979, 127.0276, 5.0);

        // 각 pageId에 codes가 있어야 함
        result.forEach((pageId, codes) -> {
            assertFalse(codes.isEmpty());
            System.out.println("pageId: " + pageId + " → codes: " + codes);
        });
    }

    @Test
    void testSearchRadiusCodesByPageId_빈페이지_제외() {
        manager.put(37.4979, 127.0276, "B0001".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        Map<Integer, List<String>> result =
                manager.searchRadiusCodesByPageId(37.4979, 127.0276, 5.0);

        // 빈 pageId는 포함되면 안 됨
        result.forEach((pageId, codes) -> assertFalse(codes.isEmpty()));
    }

    @Test
    void testSearchRadiusCodesByPageId_범위밖_미포함() {
        // 강남 삽입
        manager.put(37.4979, 127.0276, "B0001".getBytes());
        // 부산 삽입 (반경 밖)
        manager.put(35.1796, 129.0756, "B9999".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        Map<Integer, List<String>> result =
                manager.searchRadiusCodesByPageId(37.4979, 127.0276, 5.0);

        List<String> allCodes = result.values().stream()
                .flatMap(List::stream)
                .toList();

        assertTrue(allCodes.contains("B0001"));
        assertFalse(allCodes.contains("B9999"));
    }
}
