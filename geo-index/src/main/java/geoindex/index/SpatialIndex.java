package geoindex.index;

import java.util.List;

public interface SpatialIndex {

    long toPageId(double lat, double lng);

    List<Long> getPageIds(double lat, double lng, double radiusKm);
}
