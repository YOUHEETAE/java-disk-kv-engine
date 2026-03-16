package geoindex.index;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GeoHashIndex implements SpatialIndex {

    private static final int PRECISION = 6;

    @Override
    public int toPageId(double lat, double lng) {
        return (int) GeoHash.toMorton(lat, lng, PRECISION);
    }

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

        Set<Integer> pageSet = new HashSet<>();
        for (long latBits = minLatBits; latBits <= maxLatBits; latBits++) {
            for (long lngBits = minLngBits; lngBits <= maxLngBits; lngBits++) {
                long morton = GeoHash.interleave(lngBits, latBits);
                pageSet.add((int) morton);
            }
        }
        return new ArrayList<>(pageSet);
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
