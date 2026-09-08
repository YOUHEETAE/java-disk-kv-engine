package geoindex.index;

import java.util.*;

public class GeoHashIndex implements SpatialIndex {

    /**
     * 축당 비트 수. 격자는 2^15 × 2^15 = 32,768 × 32,768 칸이 된다.
     * 위경도 합쳐 30비트라 pageId 가 int 범위에 들어간다 — 16 이상이면 넘친다.
     *
     * 이 엔진은 GeoHash 문자열을 만들지 않고 Morton 정수만 만들므로,
     * base32 문자 수를 뜻하는 precision 대신 축당 비트를 직접 쓴다.
     */
    private static final int BITS_PER_AXIS = 15;

    private static final long MAX_GRID_INDEX = (1L << BITS_PER_AXIS) - 1;   // 클램핑용

    @Override
    public int toPageId(double lat, double lng) {
        return (int) GeoHash.toMorton(lat, lng, BITS_PER_AXIS);
    }

    /**
     * 반경을 덮는 격자를 열거해 pageId 목록을 만든다.
     *
     * Set 이 아닌 이유: interleave 가 전단사라 서로 다른 격자 쌍이 같은 값을 낼 수 없다.
     * 중복 제거는 필요 없고, 필요한 건 정렬이다 — flush 가 파일을 pageId 순으로 배치하므로
     * 조회도 오름차순으로 읽어야 그 배치와 OS readahead 를 활용한다.
     * 격자 순회 순서는 Morton 인터리빙 때문에 오름차순이 아니라 정렬이 따로 필요하다.
     */
    @Override
    public List<Integer> getPageIds(double lat, double lng, double radiusKm) {
        double deltaDegreeY = radiusKm / 110.0;
        double kmPerDegreeLon = 111.32 * Math.cos(Math.toRadians(lat));
        double deltaDegreeX = radiusKm / kmPerDegreeLon;

        double minLat = lat - deltaDegreeY;
        double maxLat = lat + deltaDegreeY;
        double minLng = lng - deltaDegreeX;
        double maxLng = lng + deltaDegreeX;

        // 위도/경도를 직접 비트로 변환 (deinterleave 사용 안 함)
        //
        // 사방으로 한 칸씩 넓히는 이유: latToBits 가 (long) 캐스팅으로 내림하므로,
        // MBR 경계가 칸 경계에 걸치면 그 칸이 범위에서 빠진다. 그러면 반경 안의
        // 데이터가 조용히 누락된다 — 예외도 로그도 없이 결과만 줄어든다.
        //
        // 대가는 후보 증폭이고, 반경이 작을수록 크다.
        //   반경 1km : 3×4 = 12칸  → 5×6 = 30칸  (2.5배)
        //   반경 5km : 13×15       → 15×17       (1.3배)
        // 늘어난 칸은 대부분 데이터가 없어 findPage 가 null 을 돌려주므로 할당은 없다.
        // 조회 횟수만 늘어난다.
        //
        // 줄이려면 경계가 칸 경계에 실제로 가까울 때만 확장하면 된다. 다만 잘못 줄이면
        // 위의 조용한 누락이 되살아나므로, 경계 케이스 테스트를 먼저 갖춘 뒤에 손댄다.
        long minLatBits = Math.max(0, latToBits(minLat) - 1);
        long maxLatBits = Math.min(MAX_GRID_INDEX, latToBits(maxLat) + 1);
        long minLngBits = Math.max(0, lngToBits(minLng) - 1);
        long maxLngBits = Math.min(MAX_GRID_INDEX, lngToBits(maxLng) + 1);

        List<Integer> pageIds = new ArrayList<>();
        for (long latBits = minLatBits; latBits <= maxLatBits; latBits++) {
            for (long lngBits = minLngBits; lngBits <= maxLngBits; lngBits++) {
                long morton = GeoHash.interleave(lngBits, latBits, BITS_PER_AXIS);
                pageIds.add((int) morton);
            }
        }
        Collections.sort(pageIds);
        return pageIds;
    }

    // 위도 → 비트 (0 ~ 2^15 - 1)
    private long latToBits(double lat) {
        double ratio = (lat + 90.0) / 180.0;
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        return Math.min((long) (ratio * (MAX_GRID_INDEX + 1)), MAX_GRID_INDEX);
    }

    // 경도 → 비트 (0 ~ 2^15 - 1)
    private long lngToBits(double lng) {
        double ratio = (lng + 180.0) / 360.0;
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        return Math.min((long) (ratio * (MAX_GRID_INDEX + 1)), MAX_GRID_INDEX);
    }
}
