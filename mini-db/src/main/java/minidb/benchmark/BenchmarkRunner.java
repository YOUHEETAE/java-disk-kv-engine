package minidb.benchmark;

import minidb.index.GeoHashIndex;
import minidb.index.HilbertIndex;

import java.util.Collections;
import java.util.List;

public class BenchmarkRunner {

    private static final int[] SIZES = {10000, 20000, 30000, 50000, 79081, 100000, 200000, 500000, 1000000};

    private static final double SEARCH_LAT = 37.4979;
    private static final double SEARCH_LNG = 127.0276;
    private static final double RADIUS_KM  = 5.0;

    public static void main(String[] args) throws Exception {

        // === 1. 응답 시간 벤치마크 ===
        System.out.println("=== GeoSpatial Index Engine Benchmark ===");
        System.out.println();
        System.out.printf("%-10s %-15s %-15s %-15s%n", "건수", "Full Scan", "GeoHash", "Hilbert");
        System.out.println("-".repeat(55));

        for (int size : SIZES) {
            long fullScan = FullScanBenchmark.run(size);
            long geoHash  = GeohashBenchmark.run(size);
            long hilbert  = HilbertBenchmark.run(size);

            System.out.printf("%-10d %-15s %-15s %-15s%n",
                    size,
                    fullScan + "ms",
                    geoHash  + "ms",
                    hilbert  + "ms"
            );
        }

        System.out.println("-".repeat(55));
        System.out.println("완료");
        System.out.println();

        // === 2. Page Seek Count 비교 ===
        System.out.println("=== Page Seek Count 비교 (강남 반경 5km) ===");
        System.out.println();

        GeoHashIndex geoHashIndex = new GeoHashIndex();
        List<Integer> geoPageIds = geoHashIndex.getPageIds(SEARCH_LAT, SEARCH_LNG, RADIUS_KM);
        Collections.sort(geoPageIds);
        long geoSeek = SeekCountBenchmark.seekCount(geoPageIds);

        HilbertIndex hilbertIndex = new HilbertIndex();
        List<Integer> hilPageIds = hilbertIndex.getPageIds(SEARCH_LAT, SEARCH_LNG, RADIUS_KM);
        Collections.sort(hilPageIds);
        long hilSeek = SeekCountBenchmark.seekCount(hilPageIds);

        System.out.printf("%-25s %-12s %-15s%n", "방식", "PageId 수", "Seek Count");
        System.out.println("-".repeat(52));
        System.out.printf("%-25s %-12d %-15d%n", "GeoHash",                geoPageIds.size(), geoSeek);
        System.out.printf("%-25s %-12d %-15d%n", "Hilbert Multi-Interval", hilPageIds.size(), hilSeek);
        System.out.println("-".repeat(52));
        System.out.printf("Hilbert가 GeoHash 대비 Seek Count %.1fx 적음%n",
                (double) geoSeek / Math.max(hilSeek, 1));
    }
}