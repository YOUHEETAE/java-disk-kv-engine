package geoindex.test;

import geoindex.api.PageResult;
import geoindex.api.SpatialRecordManager;
import geoindex.buffer.CacheManager;
import geoindex.cache.SpatialCacheEngine;
import geoindex.index.GeoHashIndex;
import geoindex.storage.DiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpatialCacheEngineTest {

    static final String TEST_FILE = "test_cache_engine.db";
    DiskManager diskManager;
    CacheManager cacheManager;
    SpatialRecordManager spatialRecordManager;
    SpatialCacheEngine<String> engine;

    @BeforeEach
    void setup() throws Exception {
        diskManager = new DiskManager(TEST_FILE);
        cacheManager = new CacheManager(diskManager);
        GeoHashIndex geoHashIndex = new GeoHashIndex();
        spatialRecordManager = new SpatialRecordManager(cacheManager, geoHashIndex);

        // coordExtractor: "lat,lng,code" 형식 가정
        engine = new SpatialCacheEngine<>(
                spatialRecordManager,
                geoHashIndex,
                item -> {
                    String[] parts = item.split(",");
                    return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
                }
        );
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
}