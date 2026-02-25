package minidb.index;

import java.util.ArrayList;
import java.util.List;

/**
 * 힐버트 커브 기반 SpatialIndex 구현체
 *
 * toPageId:   힐버트값 / RANGE_PER_PAGE → PageId (삽입 시)
 * getPageIds: 힐버트값 선형 범위 → PageId (검색 시)
 *
 * Why / RANGE_PER_PAGE?
 *   % MAX_PAGE → 힐버트값을 접어버림 → 연속성 파괴 → Full Scan 退化
 *   / RANGE_PER_PAGE → 선형 분할 → 연속성 보존 → PageId 범위 검색 가능
 *
 * getPageIds 방식 선택: 선형 범위 vs 격자 순회
 *
 *   격자 순회 방식 (시도했으나 채택 안 함):
 *     반경 안 격자(x,y) 직접 순회 → 힐버트값 → PageId
 *     후보 수: 94건 (GeoHash 1,379건 대비 압도적으로 적음)
 *     검색 시간: 37ms → 반경 안 격자 447,975번 × xy2d 15번 = 671만 연산 병목
 *     → GeoHash(0~16ms)보다 느림
 *
 *   선형 범위 방식 (채택):
 *     centerH ± delta → PageId 범위
 *     후보 수: 2,102건
 *     검색 시간: 0~16ms
 *     → 연산 비용 최소, GeoHash와 유사한 성능
 *
 *   트레이드오프:
 *     힐버트 이론적 장점(후보 수)은 격자 순회로 증명됨
 *     하지만 격자 순회 연산 비용 > I/O 절감 효과 (현재 규모에서)
 *     대용량 + HDD 환경에서는 격자 순회 방식이 유리할 수 있음
 */
public class HilbertIndex implements SpatialIndex {

    private static final int MAX_PAGE = 10000;
    private static final long N = 1L << 15; // 32768

    private static final long MAX_HILBERT = N * N;
    private static final long RANGE_PER_PAGE = MAX_HILBERT / MAX_PAGE;

    // 위도 방향: 5.5도 × 110km = 605km → 32768격자 → 0.0185km/격자
    private static final double KM_PER_GRID_LAT = 0.0185;

    @Override
    public int toPageId(double lat, double lng) {
        long h = HilbertCurve.encode(lat, lng);
        return (int) Math.min(h / RANGE_PER_PAGE, MAX_PAGE - 1);
    }

    /**
     * 반경 내 PageId 목록 반환 (선형 범위 방식)
     *
     * 흐름:
     *   1. 중심 좌표 → 힐버트값 centerH
     *   2. 반경 → steps → delta
     *   3. (centerH - delta) ~ (centerH + delta) → PageId 범위
     *   4. minPageId ~ maxPageId 순회
     *
     * Why delta = steps * steps * 200?
     *   힐버트 경계 점프: 사분면 경계에서 힐버트값이 크게 점프
     *   강남 기준 실측 최대 delta: 12,490,934
     *   steps * steps * 200 = 14,364,800 → 커버
     *
     * TODO: 좌표마다 힐버트 경계 위치가 다름
     *   현재는 강남 기준 실측값 하드코딩
     *   실제 서비스 적용 시 동적 delta 계산 필요:
     *     중심 → 8방향 경계 좌표 힐버트값 실측 → max delta 사용
     */
    @Override
    public List<Integer> getPageIds(double lat, double lng, double radiusKm) {
        long centerH = HilbertCurve.encode(lat, lng);
        long steps = (long) Math.ceil(radiusKm / KM_PER_GRID_LAT);
        long delta = steps * steps * 200;

        long minH = Math.max(0, centerH - delta);
        long maxH = Math.min(MAX_HILBERT - 1, centerH + delta);

        int minPageId = (int)(minH / RANGE_PER_PAGE);
        int maxPageId = (int) Math.min(maxH / RANGE_PER_PAGE, MAX_PAGE - 1);

        List<Integer> pageIds = new ArrayList<>();
        for (int p = minPageId; p <= maxPageId; p++) {
            pageIds.add(p);
        }
        return pageIds;
    }
}