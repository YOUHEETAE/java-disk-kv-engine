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
                pageSet.add((int) morton);
            }
        }
        return new ArrayList<>(pageSet);
    }
}