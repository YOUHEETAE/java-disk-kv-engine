package minidb.benchmark;

import minidb.index.GeoHashIndex;
import minidb.index.HilbertIndex;

import java.util.Collections;
import java.util.List;

/**
 * Page Seek Count 비교
 *
 * Page Seek Count란?
 *   읽은 PageId 목록에서 인접한 PageId 간의 거리(jump) 합산
 *   → 디스크 헤드가 얼마나 이동했는가를 나타냄
 *
 * 낮을수록 순차 I/O에 가깝다 (좋음)
 * 높을수록 랜덤 I/O가 많다 (나쁨)
 *
 * 예시:
 *   GeoHash: [5 → 402 → 11 → 390] → |5-402|+|402-11|+|11-390| = 1,165
 *   Hilbert: [120 → 121 → 122 → 123] → 1+1+1 = 3
 */
public class SeekCountBenchmark {

    private static final double SEARCH_LAT   = 37.4979;
    private static final double SEARCH_LNG   = 127.0276;
    private static final double RADIUS_KM    = 5.0;

    public static void main(String[] args) {
        System.out.println("=== Page Seek Count 비교 ===");
        System.out.println("기준: 강남 좌표 반경 5km");
        System.out.println();

        // GeoHash
        GeoHashIndex geoHashIndex = new GeoHashIndex();
        List<Integer> geoPageIds = geoHashIndex.getPageIds(SEARCH_LAT, SEARCH_LNG, RADIUS_KM);
        Collections.sort(geoPageIds);
        long geoSeek = seekCount(geoPageIds);

        System.out.println("[GeoHash]");
        System.out.println("  PageId 수:      " + geoPageIds.size());
        System.out.println("  Page Seek Count: " + geoSeek);
        System.out.println("  PageId 샘플:     " + sample(geoPageIds));
        System.out.println();

        // Hilbert
        HilbertIndex hilbertIndex = new HilbertIndex();
        List<Integer> hilPageIds = hilbertIndex.getPageIds(SEARCH_LAT, SEARCH_LNG, RADIUS_KM);
        Collections.sort(hilPageIds);
        long hilSeek = seekCount(hilPageIds);

        System.out.println("[Hilbert Multi-Interval]");
        System.out.println("  PageId 수:      " + hilPageIds.size());
        System.out.println("  Page Seek Count: " + hilSeek);
        System.out.println("  PageId 목록:     " + hilPageIds);
        System.out.println();

        System.out.printf("GeoHash 대비 Hilbert Seek Count: %.1fx 적음%n",
                (double) geoSeek / Math.max(hilSeek, 1));
    }

    /**
     * PageId 목록의 Seek Count 계산
     * = 인접한 PageId 간 거리(|p[i+1] - p[i]|)의 합
     */
    public static long seekCount(List<Integer> pageIds) {
        if (pageIds.size() <= 1) return 0;
        long total = 0;
        for (int i = 1; i < pageIds.size(); i++) {
            total += Math.abs(pageIds.get(i) - pageIds.get(i - 1));
        }
        return total;
    }

    /** 출력용: 앞 5개 샘플 */
    private static String sample(List<Integer> list) {
        int n = Math.min(5, list.size());
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            sb.append(list.get(i));
            if (i < n - 1) sb.append(", ");
        }
        if (list.size() > n) sb.append(", ...");
        sb.append("]");
        return sb.toString();
    }
}