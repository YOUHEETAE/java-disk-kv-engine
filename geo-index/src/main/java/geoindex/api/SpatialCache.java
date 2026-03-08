package geoindex.api;

import java.util.List;

public interface SpatialCache<T> {
    List<PageResult<T>> search(double lat, double lng, double radiusKm);

    void put(List<T> data);
}
