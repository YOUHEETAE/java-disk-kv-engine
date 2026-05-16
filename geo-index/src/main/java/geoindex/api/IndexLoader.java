package geoindex.api;

@FunctionalInterface
public interface IndexLoader {
    void put(double lat, double lng, String code);
}