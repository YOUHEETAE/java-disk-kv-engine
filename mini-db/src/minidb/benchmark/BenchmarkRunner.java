package minidb.benchmark;

import java.util.List;

public class BenchmarkRunner {

    private static final int[] SIZES = {10000, 20000, 30000, 50000, 79081, 100000, 200000, 500000, 1000000};

    public static void main(String[] args) throws Exception {
        System.out.println("=== GeoSpatial Index Engine Benchmark ===");
        System.out.println();
        System.out.printf("%-10s %-15s %-15s%n", "건수", "Full Scan", "GeoHash");
        System.out.println("-".repeat(40));

        for (int size : SIZES) {
            long fullScan = FullScanBenchmark.run(size);
            long geoHash = GeohashBenchmark.run(size);

            System.out.printf("%-10d %-15s %-15s%n",
                    size,
                    fullScan + "ms",
                    geoHash + "ms"
            );
        }

        System.out.println("-".repeat(40));
        System.out.println("완료");
    }
}