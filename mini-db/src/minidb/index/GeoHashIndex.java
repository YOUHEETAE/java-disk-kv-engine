package minidb.index;

import java.util.List;

public class GeoHashIndex implements SpatialIndex{
    @Override
    public int toPageId(double lat, double lng) {
        return 0;
    }

    @Override
    public List<Integer> getPageIds(double lat, double lng, double radiusKm) {
        return List.of();
    }
}
