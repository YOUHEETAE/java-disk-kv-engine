package geoindex.benchmark;

import geoindex.buffer.CacheManager;
import geoindex.metric.EngineMetrics;
import geoindex.storage.DiskManager;
import geoindex.util.GeoUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static geoindex.benchmark.DummyDataGenerator.generateDummyList;

public class FullScanBenchmark {
    private final static String TEST_DB = "miniDb";
    private final static int WARMUP = 5, MEASURE = 20;

    public static BenchmarkResult run(int count) throws Exception {
        DiskManager diskManager;
        CacheManager cacheManager = null;

        try {
            List<Hospital> hospitals = generateDummyList(count);

            EngineMetrics metrics = new EngineMetrics();
            diskManager = new DiskManager(TEST_DB, metrics);
            cacheManager = new CacheManager(diskManager, metrics);
            RecordManager recordManager = new RecordManager(cacheManager);

            for (Hospital hospital : hospitals) {
                recordManager.put(hospital.hospitalCode, Hospital.toBytes(hospital));
            }

            cacheManager.flush();
            cacheManager.clearCache();

            double searchLat = 37.4979;
            double searchLng = 127.0276;
            double radiusKm = 5.0;

            for (int i = 0; i < WARMUP; i++) {
                search(searchLat, searchLng, radiusKm, recordManager);
            }

            long[] sample = new long[MEASURE];
            int matched = 0;

            for (int i = 0; i < MEASURE; i++) {
                long searchStart = System.nanoTime();
                matched = search(searchLat, searchLng, radiusKm, recordManager);
                sample[i] = System.nanoTime() - searchStart;
            }
            Arrays.sort(sample);
            long medianNs = sample[MEASURE / 2];
            int candidates = recordManager.getAllValues().size();

            return new BenchmarkResult(medianNs, candidates, matched);

        } finally {
            if (cacheManager != null) cacheManager.close();
            Files.deleteIfExists(Path.of(TEST_DB));
        }
    }

    private static int search(double searchLat, double searchLng,
                               double radiusKm, RecordManager recordManager) {
        int matched = 0;
        for (byte[] values : recordManager.getAllValues()) {
            Hospital hospital = Hospital.fromBytes("", values);
            double distance = GeoUtils.haversine(searchLat, searchLng, hospital.coordinateY, hospital.coordinateX);
            if(distance < radiusKm) matched ++;
        }
        return matched;
    }
}
