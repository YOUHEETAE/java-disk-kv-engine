package minidb.index;

import java.util.ArrayList;
import java.util.List;

/**
 * 힐버트 커브 기반 SpatialIndex 구현체
 *
 * Why / RANGE_PER_PAGE 방식?
 *   % MAX_PAGE → 힐버트 연속성 파괴 (경계에서 PageId 점프)
 *   / RANGE_PER_PAGE → 힐버트값을 선형 분할 → 연속성 보존
 *
 *   PageId = hilbert / RANGE_PER_PAGE
 *   → 힐버트값이 가까우면 PageId도 가까움
 *   → getPageIds: minPageId ~ maxPageId 단순 범위로 계산 (232번 루프)
 */
public class HilbertIndex implements SpatialIndex {

    private static final int MAX_PAGE = 10000;
    private static final long N = 1L << 15;           // 32768

    // 힐버트값 전체 범위: 2^30 ≈ 10억
    private static final long MAX_HILBERT = N * N;

    // 페이지 하나가 담당하는 힐버트값 범위
    // 1,073,741,824 / 10,000 = 107,374
    private static final long RANGE_PER_PAGE = MAX_HILBERT / MAX_PAGE;

    // 한국 위도 범위: 5.5도 ≈ 611km → 32768 격자 → 1격자 ≈ 0.0187km
    private static final double KM_PER_GRID = 0.0187;

    @Override
    public int toPageId(double lat, double lng) {
        long h = HilbertCurve.encode(lat, lng);
        int pageId = (int)(h / RANGE_PER_PAGE);
        return Math.min(pageId, MAX_PAGE - 1); // 범위 초과 방지
    }

    /**
     * 반경 내 PageId 목록 반환
     *
     * 흐름:
     *   1. 중심 좌표 → 힐버트값 → 중심 PageId
     *   2. 반경 → 격자 칸 수(steps) → 힐버트 delta
     *   3. (centerH - delta) ~ (centerH + delta) → PageId 범위
     *   4. minPageId ~ maxPageId 순회
     *
     * Why delta = steps * steps?
     *   실측: 남쪽 5km 이동 시 힐버트값 최대 12,490,934 차이
     *   steps * steps = 268 * 268 = 71,824 → delta로 부족
     *   실측 최대값(12,490,934)을 커버하려면 steps * 200 수준 필요
     *   → 안전하게 실측 기반 상수 사용
     */
    @Override
    public List<Integer> getPageIds(double lat, double lng, double radiusKm) {
        long centerH = HilbertCurve.encode(lat, lng);

        // 반경 → 격자 칸 수 → 힐버트 delta (실측 기반)
        long steps = (long) Math.ceil(radiusKm / KM_PER_GRID);

        // TODO: 좌표마다 힐버트 경계 위치가 다름
        // 현재는 강남 기준 실측값(steps * steps * 200) 하드코딩
        // 실제 서비스 적용 시 동적 delta 계산 필요
        long delta = steps * steps * 200;

        long minH = Math.max(0, centerH - delta);
        long maxH = Math.min(MAX_HILBERT - 1, centerH + delta);

        int minPageId = (int)(minH / RANGE_PER_PAGE);
        int maxPageId = (int)(maxH / RANGE_PER_PAGE);
        maxPageId = Math.min(maxPageId, MAX_PAGE - 1);

        List<Integer> pageIds = new ArrayList<>();
        for (int p = minPageId; p <= maxPageId; p++) {
            pageIds.add(p);
        }

        return pageIds;
    }
}