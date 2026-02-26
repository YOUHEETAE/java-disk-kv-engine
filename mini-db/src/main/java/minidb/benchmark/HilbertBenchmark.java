package minidb.benchmark;

import minidb.api.SpatialRecordManager;
import minidb.buffer.CacheManager;
import minidb.index.HilbertIndex;
import minidb.storage.DiskManager;
import minidb.util.GeoUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static minidb.benchmark.DummyDataGenerator.generateDummyList;

public class HilbertBenchmark {
    private static final String TEST_DB = "hilbertDb";

    public static long run(int count) throws Exception {
        CacheManager cacheManager = null;

        try {
            List<Hospital> hospitals = generateDummyList(count);

            DiskManager diskManager = new DiskManager(TEST_DB);
            cacheManager = new CacheManager(diskManager);
            SpatialRecordManager manager = new SpatialRecordManager(cacheManager, new HilbertIndex());

            for (Hospital hospital : hospitals) {
                manager.put(hospital.coordinateY, hospital.coordinateX, Hospital.toBytes(hospital));
            }

            cacheManager.flush();
            cacheManager.clearCache();

            double searchLat = 37.4979;
            double searchLng = 127.0276;
            double radiusKm = 5.0;

            long searchStart = System.currentTimeMillis();

            for (byte[] values : manager.searchRadius(searchLat, searchLng, radiusKm)) {
                Hospital hospital = Hospital.fromBytes("", values);
                GeoUtils.haversine(searchLat, searchLng, hospital.coordinateY, hospital.coordinateX);
            }

            return System.currentTimeMillis() - searchStart;

        } finally {
            if (cacheManager != null) cacheManager.close();
            Files.deleteIfExists(Path.of(TEST_DB));
        }
    }

    public static void main(String[] args) throws Exception {
        CacheManager cacheManager = null;

        try {
            System.out.println("generating hilbert dummy data");
            List<Hospital> hospitals = generateDummyList(79081);
            System.out.println("generating " + hospitals.size() + " dummy data");

            DiskManager diskManager = new DiskManager(TEST_DB);
            cacheManager = new CacheManager(diskManager);
            SpatialRecordManager manager = new SpatialRecordManager(cacheManager, new HilbertIndex());

            long startTime = System.currentTimeMillis();
            for (Hospital hospital : hospitals) {
                manager.put(hospital.coordinateY, hospital.coordinateX, Hospital.toBytes(hospital));
            }
            System.out.println("hilbert dummy data inserted in " + (System.currentTimeMillis() - startTime) + "ms");

            cacheManager.flush();
            cacheManager.clearCache();

            double searchLat = 37.4979;
            double searchLng = 127.0276;
            double radiusKm = 5.0;

            long searchStart = System.currentTimeMillis();

            List<byte[]> candidates = manager.searchRadius(searchLat, searchLng, radiusKm);
            System.out.println("후보 수 (원형 필터링 전): " + candidates.size() + "건");

            int count = 0;
            for (byte[] values : candidates) {
                Hospital hospital = Hospital.fromBytes("", values);
                if (GeoUtils.haversine(searchLat, searchLng, hospital.coordinateY, hospital.coordinateX) <= radiusKm) {
                    count++;
                }
            }

            System.out.println("Hilbert 검색: " + (System.currentTimeMillis() - searchStart) + "ms");
            System.out.println("결과: " + count + "건");

        } finally {
            if (cacheManager != null) cacheManager.close();
            Files.deleteIfExists(Path.of(TEST_DB));
        }
    }
}