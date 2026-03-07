package geoindex.index;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GeoHashIndex implements SpatialIndex {

    private static final int PRECISION = 6;

    // morton 30비트 중 상위 14비트를 PageId로 사용
    // SHIFT = 16 → PageId 범위 = 2^14 = 16,384
    // 79,081건 / 16,384페이지 = 평균 4.8건/페이지
    private static final int SHIFT = 15;

    @Override
    public int toPageId(double lat, double lng) {
        long morton = GeoHash.toMorton(lat, lng, PRECISION);
        return (int)(morton >> SHIFT);
    }


    @Override
    public List<Integer> getPageIds(double lat, double lng, double radiusKm) {
        double deltaDegreeY = radiusKm / 110.0;
        double kmPerDegreeLon = 111.32 * Math.cos(Math.toRadians(lat));
        double deltaDegreeX = radiusKm / kmPerDegreeLon;

        // 네 꼭짓점 모두 계산
        long[] corners = {
                GeoHash.toMorton(lat - deltaDegreeY, lng - deltaDegreeX, PRECISION),
                GeoHash.toMorton(lat - deltaDegreeY, lng + deltaDegreeX, PRECISION),
                GeoHash.toMorton(lat + deltaDegreeY, lng - deltaDegreeX, PRECISION),
                GeoHash.toMorton(lat + deltaDegreeY, lng + deltaDegreeX, PRECISION)
        };

        long minLngBits = Long.MAX_VALUE, maxLngBits = Long.MIN_VALUE;
        long minLatBits = Long.MAX_VALUE, maxLatBits = Long.MIN_VALUE;

        for (long morton : corners) {
            long[] bits = GeoHash.deinterleave(morton);
            minLngBits = Math.min(minLngBits, bits[0]);
            maxLngBits = Math.max(maxLngBits, bits[0]);
            minLatBits = Math.min(minLatBits, bits[1]);
            maxLatBits = Math.max(maxLatBits, bits[1]);
        }


        Set<Integer> pageSet = new HashSet<>();
        for (long latBits = minLatBits; latBits <= maxLatBits; latBits++) {
            for (long lngBits = minLngBits; lngBits <= maxLngBits; lngBits++) {
                long morton = GeoHash.interleave(lngBits, latBits);
                int pageId = (int)(morton >> SHIFT);
                System.out.println("latBits=" + latBits + " lngBits=" + lngBits + " pageId=" + pageId);
                pageSet.add(pageId);
            }
        }
        System.out.println("pageIds 수: " + pageSet.size());
        System.out.println("pageIds 수: " + pageSet.size());
        System.out.println("minLatBits=" + minLatBits + " maxLatBits=" + maxLatBits);
        System.out.println("minLngBits=" + minLngBits + " maxLngBits=" + maxLngBits);
        System.out.println("lat loop 횟수: " + (maxLatBits - minLatBits + 1));
        System.out.println("lng loop 횟수: " + (maxLngBits - minLngBits + 1));
        return new ArrayList<>(pageSet);
    }
}
