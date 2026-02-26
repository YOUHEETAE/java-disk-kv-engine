package minidb.index;

import java.util.ArrayList;
import java.util.List;

public class GeoHashIndex implements SpatialIndex{
    private static final int PRECISION = 6;
    private static final int MAX_PAGE = 10000;
    private static final double CELL_SIZE_KM = 1.2;

    @Override
    public int toPageId(double lat, double lng) {
        String geohash = GeoHash.toGeohash(lat, lng, PRECISION);
        long morton = GeoHash.toLong(geohash);
        return (int)(morton % MAX_PAGE);
    }

    @Override
    public List<Integer> getPageIds(double lat, double lng, double radiusKm) {
        String geohash = GeoHash.toGeohash(lat, lng, PRECISION);
        long morton = GeoHash.toLong(geohash);

        int steps = (int) Math.ceil(radiusKm / CELL_SIZE_KM) + 1;

        List<Integer> pageIds = new ArrayList<>();

        for (int dLat = -steps; dLat <= steps; dLat++) {
            for (int dLng = -steps; dLng <= steps; dLng++) {
                long cell = GeoHash.neighbor(morton, dLat, dLng);
                if (cell != GeoHash.INVALID_CELL) {
                    int pageId = (int)(cell % MAX_PAGE);
                    if (!pageIds.contains(pageId)) {
                        pageIds.add(pageId);
                    }
                }
            }
        }
        return pageIds;
    }
}
