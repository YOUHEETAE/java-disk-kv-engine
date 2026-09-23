package geoindex.benchmark;

import geoindex.api.SpatialRecordManager;
import geoindex.buffer.CacheManager;
import geoindex.index.GeoHashIndex;
import geoindex.metric.EngineMetrics;
import geoindex.storage.DiskManager;
import geoindex.util.GeoUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static geoindex.benchmark.DummyDataGenerator.generateDummyList;

public class GeohashBenchmark {
    private final static String TEST_DB = "geoHashDb";
    private final static int WARMUP = 5, MEASURE = 20;

    public static BenchmarkResult run(int count) throws Exception {
        DiskManager diskManager;
        CacheManager cacheManager = null;

        try {
            List<Hospital> hospitals = generateDummyList(count);

            EngineMetrics metrics = new EngineMetrics();
            diskManager = new DiskManager(TEST_DB, metrics);
            cacheManager = new CacheManager(diskManager, metrics);
            SpatialRecordManager spatialRecordManager = new SpatialRecordManager(cacheManager, new GeoHashIndex(), metrics);

            for (Hospital hospital : hospitals) {
                spatialRecordManager.put(hospital.coordinateY, hospital.coordinateX, Hospital.toBytes(hospital));
            }

            cacheManager.flush();
            cacheManager.clearCache();

            double searchLat = 37.4979;
            double searchLng = 127.0276;
            double radiusKm = 5.0;

            for(int i = 0; i < WARMUP; i++) {
                search(searchLat, searchLng, radiusKm, spatialRecordManager);
            }

            long[] sample = new long[MEASURE];
            int matched = 0;

            for(int i = 0; i < MEASURE; i++) {
                long searchStart = System.nanoTime();
                matched = search(searchLat, searchLng, radiusKm, spatialRecordManager);
                sample[i] = System.nanoTime() - searchStart;
            }
            Arrays.sort(sample);
            long medianNs = sample[MEASURE / 2];
            int candidates = spatialRecordManager.searchRadius(searchLat, searchLng, radiusKm).size();

            return new BenchmarkResult(medianNs, candidates, matched);

        } finally {
            if (cacheManager != null) cacheManager.close();
            Files.deleteIfExists(Path.of(TEST_DB));
        }
    }

    private static int search(double searchLat, double searchLng,
                              double radiusKm, SpatialRecordManager spatialRecordManager) {
        int count = 0;
        for (byte[] values : spatialRecordManager.searchRadius(searchLat, searchLng, radiusKm)) {
            Hospital hospital = Hospital.fromBytes("", values);
            double distance = GeoUtils.haversine(searchLat, searchLng, hospital.coordinateY, hospital.coordinateX);
            if(distance < radiusKm) count++;
        }
        return count;
    }
}