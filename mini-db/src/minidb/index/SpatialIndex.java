package minidb.index;

import java.util.List;

public interface SpatialIndex {

    int toPageId(double lat, double lng);

    List<Integer> getPageIds(double lat, double lng, double radiusKm);
}
