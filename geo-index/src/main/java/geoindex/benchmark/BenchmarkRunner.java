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
        System.out.println("warm-up 제외 반복 측정의 중앙값 · 후보 = 거리 계산을 돌린 건수 · 결과 = 반경 안 건수");
        System.out.println();
        System.out.printf("%-10s %12s %9s %12s %9s %8s%n",
                "건수", "Full Scan", "후보", "GeoHash", "후보", "결과");
        System.out.println("-".repeat(66));

        for (int size : SIZES) {
            BenchmarkResult fullScan = FullScanBenchmark.run(size);
            BenchmarkResult geoHash  = GeohashBenchmark.run(size);

            // 두 경로는 같은 데이터에 같은 반경이니 결과 건수가 같아야 한다. 다르면 인덱스가 놓친 것이다.
            String check = fullScan.matched() == geoHash.matched()
                    ? "" : "  !! Full Scan 결과 " + fullScan.matched() + " 과 불일치";

            System.out.printf("%-10d %10.2fms %9d %10.2fms %9d %8d%s%n",
                    size,
                    fullScan.medianNs() / 1_000_000.0, fullScan.candidates(),
                    geoHash.medianNs()  / 1_000_000.0, geoHash.candidates(),
                    geoHash.matched(), check);
        }

        System.out.println("-".repeat(66));
    }
}
