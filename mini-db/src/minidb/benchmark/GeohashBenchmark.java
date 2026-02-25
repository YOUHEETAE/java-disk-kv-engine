package minidb.benchmark;

import minidb.api.SpatialRecordManager;
import minidb.buffer.CacheManager;
import minidb.index.GeoHashIndex;
import minidb.storage.DiskManager;
import minidb.util.GeoUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static minidb.benchmark.DummyDataGenerator.generateDummyList;

public class GeohashBenchmark {
    private final static String TEST_DB = "geoHashDb";

    public static long run(int count) throws Exception {
        DiskManager diskManager;
        CacheManager cacheManager = null;

        try {
            List<Hospital> hospitals = generateDummyList(count);

            diskManager = new DiskManager(TEST_DB);
            cacheManager = new CacheManager(diskManager);
            SpatialRecordManager spatialRecordManager = new SpatialRecordManager(cacheManager, new GeoHashIndex());

            for (Hospital hospital : hospitals) {
                spatialRecordManager.put(hospital.coordinateY, hospital.coordinateX, Hospital.toBytes(hospital));
            }

            cacheManager.flush();
            cacheManager.clearCache();

            double searchLat = 37.4979;
            double searchLng = 127.0276;
            double radiusKm = 5.0;

            long searchStart = System.currentTimeMillis();

            for (byte[] values : spatialRecordManager.searchRadius(searchLat, searchLng, radiusKm)) {
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

        DiskManager diskManager;
        CacheManager cacheManager = null;

        try {
            System.out.println("generating geohash dummy data");
            List<Hospital> hospitals = generateDummyList(79081);
            System.out.println("generating " + hospitals.size() + " dummy data");

            diskManager = new DiskManager(TEST_DB);
            cacheManager = new CacheManager(diskManager);
            SpatialRecordManager spatialRecordManager = new SpatialRecordManager(cacheManager, new GeoHashIndex());

            long startTime = System.currentTimeMillis();
            for (Hospital hospital : hospitals) {
                spatialRecordManager.put(hospital.coordinateY, hospital.coordinateX, Hospital.toBytes(hospital));
            }
            long endTime = System.currentTimeMillis();
            long insertTime = endTime - startTime;
            System.out.println("geohash dummy data inserted in " + insertTime + " ms");

            cacheManager.flush();
            cacheManager.clearCache();

            double searchLat = 37.4979;
            double searchLng = 127.0276;
            double radiusKm = 5.0;

            long searchStart = System.currentTimeMillis();

            List<byte[]> candidates = spatialRecordManager.searchRadius(searchLat, searchLng, radiusKm);
            System.out.println("후보 수 (원형 필터링 전): " + candidates.size() + "건");

            int count = 0;

            for (byte[] values : candidates) {
                Hospital hospital = Hospital.fromBytes("", values);
                double distance = GeoUtils.haversine(searchLat, searchLng, hospital.coordinateY, hospital.coordinateX);

                if (distance < radiusKm) {
                    count++;
                }
            }

            long searchEnd = System.currentTimeMillis();
            System.out.println("GeoHash 검색: " + (searchEnd - searchStart) + "ms");
            System.out.println("결과: " + count + "건");

        } finally {
            if (cacheManager != null) cacheManager.close();
            Files.deleteIfExists(Path.of(TEST_DB));
        }
    }
}