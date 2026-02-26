package minidb.test;

import minidb.api.SpatialRecordManager;
import minidb.buffer.CacheManager;
import minidb.index.GeoHashIndex;
import minidb.storage.DiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpatialRecordManagerTest {

    static final String TEST_FILE = "test_spatial.db";
    SpatialRecordManager manager;
    CacheManager cacheManager;
    DiskManager diskManager;

    @BeforeEach
    void setup() {
        diskManager = new DiskManager(TEST_FILE);
        cacheManager = new CacheManager(diskManager);
        manager = new SpatialRecordManager(cacheManager, new GeoHashIndex());
    }

    @AfterEach
    void cleanup() throws Exception {
        cacheManager.close();
        Files.deleteIfExists(Path.of(TEST_FILE));
    }

    @Test
    void testPutAndSearchRadius() {
        manager.put(37.4979, 127.0276, "강남병원".getBytes());
        manager.put(37.4990, 127.0280, "역삼병원".getBytes());

        manager.put(35.1796, 129.0756, "부산병원".getBytes());

        List<byte[]> results = manager.searchRadius(37.4979, 127.0276, 5.0);

        System.out.println("검색 결과 수: " + results.size());
        assertTrue(results.size() >= 2);
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
}