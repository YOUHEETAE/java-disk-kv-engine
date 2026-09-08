package geoindex.index;

import java.util.*;

public class GeoHashIndex implements SpatialIndex {

    private static final int PRECISION = 6;

    @Override
    public int toPageId(double lat, double lng) {
        return (int) GeoHash.toMorton(lat, lng, PRECISION);
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
        long minLatBits = Math.max(0, latToBits(minLat, PRECISION) - 1);
        long maxLatBits = Math.min((1L << 15) - 1, latToBits(maxLat, PRECISION) + 1);
        long minLngBits = Math.max(0, lngToBits(minLng, PRECISION) - 1);
        long maxLngBits = Math.min((1L << 15) - 1, lngToBits(maxLng, PRECISION) + 1);

        List<Integer> pageIds = new ArrayList<>();
        for (long latBits = minLatBits; latBits <= maxLatBits; latBits++) {
            for (long lngBits = minLngBits; lngBits <= maxLngBits; lngBits++) {
                long morton = GeoHash.interleave(lngBits, latBits);
                pageIds.add((int) morton);
            }
        }
        Collections.sort(pageIds);
        return pageIds;
    }

    // 위도 → 비트 (0 ~ 2^15 - 1)
    private long latToBits(double lat, int precision) {
        double ratio = (lat + 90.0) / 180.0;
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        long maxBits = 1L << (precision * 5 / 2);  // 2^15 = 32768
        return Math.min((long)(ratio * maxBits), maxBits - 1);
    }

    // 경도 → 비트 (0 ~ 2^15 - 1)
    private long lngToBits(double lng, int precision) {
        double ratio = (lng + 180.0) / 360.0;
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        long maxBits = 1L << (precision * 5 / 2);  // 2^15 = 32768
        return Math.min((long)(ratio * maxBits), maxBits - 1);
    }
}
