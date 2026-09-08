package geoindex.benchmark;

/**
 * 규모별 응답 시간 비교 — Full Scan vs GeoHash.
 *
 * 두 경로는 저장하는 값이 다르다. Full Scan 은 Hospital 레코드 전체를 담고 8만 건
 * 전부에 haversine 을 돌리며, GeoHash 경로는 병원 코드만 담고 후보 페이지만 읽는다.
 * 배수에는 색인 효과와 저장 대상 차이가 함께 들어 있다.
 *
 * Seek Count 비교 절이 있었으나 제거했다. pageId 간 거리를 재고 있었는데 그것은
 * 인덱스가 만든 번호의 성질이라 파일 배치와 무관하다. 디스크 seek 을 재려면
 * pageMap 이 배정한 파일 오프셋 간 거리를 봐야 한다.
 */
public class BenchmarkRunner {

    private static final int[] SIZES = {10000, 20000, 30000, 50000, 79081, 100000, 200000, 500000, 1000000};

    public static void main(String[] args) throws Exception {

        System.out.println("=== GeoSpatial Index Engine Benchmark ===");
        System.out.println();
        System.out.printf("%-10s %-15s %-15s%n", "건수", "Full Scan", "GeoHash");
        System.out.println("-".repeat(40));

        for (int size : SIZES) {
            long fullScan = FullScanBenchmark.run(size);
            long geoHash  = GeohashBenchmark.run(size);

            System.out.printf("%-10d %-15s %-15s%n",
                    size,
                    fullScan + "ms",
                    geoHash  + "ms"
            );
        }

        System.out.println("-".repeat(40));
        System.out.println("완료");
    }
}
