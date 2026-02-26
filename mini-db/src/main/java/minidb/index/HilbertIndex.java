package minidb.index;

import java.util.ArrayList;
import java.util.List;

/**
 * 힐버트 커브 기반 SpatialIndex 구현체
 *
 * toPageId:   힐버트값 / RANGE_PER_PAGE → PageId (삽입 시)
 * getPageIds: Multi-Interval Query (검색 시)
 *
 * Why / RANGE_PER_PAGE?
 *   % MAX_PAGE → 힐버트값을 접어버림 → 연속성 파괴 → Full Scan 退化
 *   / RANGE_PER_PAGE → 선형 분할 → 연속성 보존
 *
 * getPageIds 진화 과정:
 *
 *   1차: 선형 범위 (centerH ± delta)
 *     → delta 하드코딩 필요, 엉뚱한 지역 포함, 후보 2,102건
 *
 *   2차: 격자 순회
 *     → 후보 94건 달성, 검색 37ms (671만 연산 병목)
 *
 *   3차: Multi-Interval Query (현재)
 *     → 원형 안 격자 힐버트값 수집 → 정렬 → Interval Merge
 *     → 여러 disjoint interval → PageId 범위 union
 *     → delta 불필요, 후보 수 최소화
 *
 * Why Multi-Interval?
 *   원형 영역은 힐버트 곡선 위에서 하나의 연속 구간이 아님
 *   힐버트 경계 점프로 여러 disjoint interval로 나뉨:
 *
 *   h: 3  5  6  7  11  12  13  20  21  22
 *      ↓ Interval Merge
 *      [3], [5~7], [11~13], [20~22]
 *
 *   각 interval을 PageId 범위로 변환 → 실제 필요한 PageId만 정확히 커버
 */
public class HilbertIndex implements SpatialIndex {

    private static final int MAX_PAGE = 10000;
    private static final long N = 1L << 15; // 32768

    private static final long MAX_HILBERT = N * N;
    private static final long RANGE_PER_PAGE = MAX_HILBERT / MAX_PAGE;

    // 위도 방향: 5.5도 × 110km = 605km → 32768격자 → 0.0185km/격자
    private static final double KM_PER_GRID_LAT = 0.0185;
    // 경도 방향: 3.5도 × 88.9km = 311km → 32768격자 → 0.0095km/격자
    private static final double KM_PER_GRID_LNG = 0.0095;

    @Override
    public int toPageId(double lat, double lng) {
        long h = HilbertCurve.encode(lat, lng);
        return (int) Math.min(h / RANGE_PER_PAGE, MAX_PAGE - 1);
    }

    /**
     * Multi-Interval Query (사각형 MBR 기준)
     *
     * 원형 필터링은 프론트엔드에 위임
     * 이 엔진은 사각형(MBR) 기준 후보 반환에 집중
     *
     * 흐름:
     *   1. 중심 격자 (cx, cy) 계산
     *   2. 사각형 MBR 격자 순회 (stepsY × stepsX)
     *   3. 각 격자 → 힐버트값 → PageId visited 마킹
     *   4. visited 배열 순회 → Interval Merge
     *   5. disjoint interval → PageId 목록 반환
     */
    @Override
    public List<Integer> getPageIds(double lat, double lng, double radiusKm) {
        long cx = HilbertCurve.toGridX(lng);
        long cy = HilbertCurve.toGridY(lat);
        long stepsY = (long) Math.ceil(radiusKm / KM_PER_GRID_LAT);
        long stepsX = (long) Math.ceil(radiusKm / KM_PER_GRID_LNG);

        // Step 1: 사각형 MBR 격자 → PageId visited 마킹
        // 원형 필터링은 프론트엔드에 위임
        // PageId 범위(0~9999)가 작으므로 boolean 배열로 O(1) 마킹
        boolean[] visited = new boolean[MAX_PAGE];

        for (long dy = -stepsY; dy <= stepsY; dy++) {
            for (long dx = -stepsX; dx <= stepsX; dx++) {
                long x = cx + dx;
                long y = cy + dy;
                if (x < 0 || x >= N || y < 0 || y >= N) continue;

                long h = HilbertCurve.encodeGrid(x, y);
                int pageId = (int) Math.min(h / RANGE_PER_PAGE, MAX_PAGE - 1);
                visited[pageId] = true;
            }
        }

        // Step 2: visited 배열 순회 → Interval Merge → PageId union
        // visited는 이미 정렬된 순서(0~9999)
        List<Integer> result = new ArrayList<>();
        for (int p = 0; p < MAX_PAGE; p++) {
            if (visited[p]) result.add(p);
        }

        return result;
    }
}