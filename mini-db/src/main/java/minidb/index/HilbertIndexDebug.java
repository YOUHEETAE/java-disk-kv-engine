package minidb.index;

import java.util.ArrayList;
import java.util.List;

/**
 * 디버그용: 각 방식별 PageId 수, interval 수 출력
 */
public class HilbertIndexDebug {

    private static final int MAX_PAGE = 10000;
    private static final long N = 1L << 15;
    private static final long MAX_HILBERT = N * N;
    private static final long RANGE_PER_PAGE = MAX_HILBERT / MAX_PAGE;
    private static final double KM_PER_GRID_LAT = 0.0185;
    private static final double KM_PER_GRID_LNG = 0.0095;

    public static void main(String[] args) {
        double lat = 37.4979;
        double lng = 127.0276;
        double radiusKm = 5.0;

        System.out.println("=== 방식별 PageId 수 비교 ===\n");

        // 1. 선형 범위 방식
        {
            long centerH = HilbertCurve.encode(lat, lng);
            long steps = (long) Math.ceil(radiusKm / KM_PER_GRID_LAT);
            long delta = steps * steps * 200;
            int minP = (int)(Math.max(0, centerH - delta) / RANGE_PER_PAGE);
            int maxP = (int)(Math.min(MAX_HILBERT - 1, centerH + delta) / RANGE_PER_PAGE);
            maxP = Math.min(maxP, MAX_PAGE - 1);
            System.out.println("[1] 선형 범위 방식");
            System.out.println("    PageId 수:    " + (maxP - minP + 1));
            System.out.println("    interval 수:  1 (연속 구간)");
            System.out.println("    범위:         " + minP + " ~ " + maxP);
        }

        System.out.println();

        // 2. Multi-Interval 방식 (현재)
        {
            long cx = HilbertCurve.toGridX(lng);
            long cy = HilbertCurve.toGridY(lat);
            long stepsY = (long) Math.ceil(radiusKm / KM_PER_GRID_LAT);

            boolean[] visited = new boolean[MAX_PAGE];
            int gridCount = 0;

            for (long dy = -stepsY; dy <= stepsY; dy++) {
                double distY = dy * KM_PER_GRID_LAT;
                double remainKm = Math.sqrt(radiusKm * radiusKm - distY * distY);
                long maxDx = (long) Math.ceil(remainKm / KM_PER_GRID_LNG);
                for (long dx = -maxDx; dx <= maxDx; dx++) {
                    long x = cx + dx;
                    long y = cy + dy;
                    if (x < 0 || x >= N || y < 0 || y >= N) continue;
                    long h = HilbertCurve.encodeGrid(x, y);
                    int pageId = (int) Math.min(h / RANGE_PER_PAGE, MAX_PAGE - 1);
                    visited[pageId] = true;
                    gridCount++;
                }
            }

            // PageId 수, interval 수 계산
            int pageIdCount = 0;
            int intervalCount = 0;
            boolean inInterval = false;
            List<int[]> intervals = new ArrayList<>();
            int iStart = -1;

            for (int p = 0; p < MAX_PAGE; p++) {
                if (visited[p]) {
                    pageIdCount++;
                    if (!inInterval) {
                        inInterval = true;
                        iStart = p;
                        intervalCount++;
                    }
                } else {
                    if (inInterval) {
                        intervals.add(new int[]{iStart, p - 1});
                        inInterval = false;
                    }
                }
            }
            if (inInterval) intervals.add(new int[]{iStart, MAX_PAGE - 1});

            System.out.println("[2] Multi-Interval 방식 (현재)");
            System.out.println("    격자 순회 수: " + gridCount);
            System.out.println("    PageId 수:    " + pageIdCount);
            System.out.println("    interval 수:  " + intervalCount);
            System.out.println("    intervals:");
            for (int[] iv : intervals) {
                System.out.println("      [" + iv[0] + " ~ " + iv[1] + "] (" + (iv[1]-iv[0]+1) + "개)");
            }
        }

        System.out.println();

        // 3. GeoHash 비교
        {
            GeoHashIndex geoHash = new GeoHashIndex();
            List<Integer> pageIds = geoHash.getPageIds(lat, lng, radiusKm);
            System.out.println("[3] GeoHash 방식");
            System.out.println("    PageId 수:    " + pageIds.size());
            System.out.println("    interval 수:  분산 (neighbor 방식)");
        }
    }
}