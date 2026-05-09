package geoindex.api;

import geoindex.metric.MetricsSnapshot;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public abstract class AbstractSpatialCacheEngine<T> {

    protected final SpatialCacheEngine<T> spatialCacheEngine;
    protected int warmupSize = 3000;

    protected AbstractSpatialCacheEngine(SpatialCacheEngine<T> spatialCacheEngine) {
        this.spatialCacheEngine = spatialCacheEngine;
    }

    // -------------------------------------------------------------------------
    // 자식이 구현해야 하는 메서드
    // -------------------------------------------------------------------------

    protected abstract Map<String, T> loadByCodes(List<String> codes);
    protected abstract String  getCode(T item);
    protected abstract double  getLat(T item);
    protected abstract double  getLng(T item);

    // 좌표가 null인 데이터 제외 시 오버라이드
    protected boolean isValid(T item) { return true; }

    // -------------------------------------------------------------------------
    // 공통 로직
    // -------------------------------------------------------------------------

    public List<T> search(double lat, double lng, double radiusKm) {
        List<T> results = spatialCacheEngine.search(lat, lng, radiusKm,
                codes -> loadByCodes(codes));

        double[] mbr = calcMBR(lat, lng, radiusKm);
        return results.stream()
                .filter(item -> getLng(item) >= mbr[0] && getLng(item) <= mbr[1]
                             && getLat(item) >= mbr[2] && getLat(item) <= mbr[3])
                .collect(Collectors.toList());
    }

    public void rebuild(Supplier<List<T>> allLoader) {
        spatialCacheEngine.rebuild(srm ->
            allLoader.get().stream()
                    .filter(this::isValid)
                    .forEach(item ->
                        srm.put(getLat(item), getLng(item), getCode(item).getBytes())
                    )
        );
        warmup();
    }

    public void warmup() {
        Map<Integer, List<String>> targets = spatialCacheEngine.getWarmupTargets(warmupSize);
        List<String> allCodes = targets.values().stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        int chunkSize = 1000;
        Map<String, T> byCode = new HashMap<>();
        for (int i = 0; i < allCodes.size(); i += chunkSize) {
            List<String> chunk = allCodes.subList(i, Math.min(i + chunkSize, allCodes.size()));
            byCode.putAll(loadByCodes(chunk));
        }

        targets.forEach((pageId, codes) -> {
            List<T> data = codes.stream()
                    .map(byCode::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            spatialCacheEngine.putCache(pageId, data);
        });
    }

    public void shutdown() {
        spatialCacheEngine.persistWarmup();
    }

    public MetricsSnapshot getMetrics() {
        return spatialCacheEngine.getMetrics();
    }

    // -------------------------------------------------------------------------
    // 유틸
    // -------------------------------------------------------------------------

    private double[] calcMBR(double lat, double lng, double radiusKm) {
        double deltaLat = radiusKm / 110.0;
        double deltaLng = radiusKm / (111.32 * Math.cos(Math.toRadians(lat)));
        return new double[]{ lng - deltaLng, lng + deltaLng, lat - deltaLat, lat + deltaLat };
    }
}
