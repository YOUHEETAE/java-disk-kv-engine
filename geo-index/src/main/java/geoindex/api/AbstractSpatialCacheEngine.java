package geoindex.api;

import geoindex.metric.MetricsSnapshot;

import java.util.*;
import java.util.function.Consumer;
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

    // -------------------------------------------------------------------------
    // 공통 로직
    // -------------------------------------------------------------------------

    /**
     * 반경을 커버하는 pageId 범위의 후보 결과를 반환한다.
     * 경계 셀의 정확한 필터링(MBR / 원형)은 구현체가 담당한다.
     */
    public List<T> search(double lat, double lng, double radiusKm) {
        return spatialCacheEngine.search(lat, lng, radiusKm, codes -> loadByCodes(codes));
    }

    public void rebuild(Consumer<IndexLoader> supplier) {
        spatialCacheEngine.rebuild(srm ->
            supplier.accept((lat, lng, code) -> srm.put(lat, lng, code.getBytes()))
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
