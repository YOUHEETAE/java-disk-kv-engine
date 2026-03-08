package geoindex.cache;

import geoindex.api.PageResult;
import geoindex.api.SpatialCache;
import geoindex.api.SpatialRecordManager;
import geoindex.index.SpatialIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class SpatialCacheEngine<T> implements SpatialCache<T> {

    private final SpatialRecordManager spatialRecordManager;
    private final SpatialIndex spatialIndex;
    private final Function<T, double[]> coordExtractor; // T → [lat, lng]
    private final ConcurrentHashMap<Integer, List<T>> pageCache = new ConcurrentHashMap<>();

    public SpatialCacheEngine(
            SpatialRecordManager spatialRecordManager,
            SpatialIndex spatialIndex,
            Function<T, double[]> coordExtractor) {
        this.spatialRecordManager = spatialRecordManager;
        this.spatialIndex = spatialIndex;
        this.coordExtractor = coordExtractor;
    }

    @Override
    public List<PageResult<T>> search(double lat, double lng, double radiusKm) {
        Map<Integer, List<String>> codesByPageId =
                spatialRecordManager.searchRadiusCodesByPageId(lat, lng, radiusKm);

        List<PageResult<T>> results = new ArrayList<>();

        for (Map.Entry<Integer, List<String>> entry : codesByPageId.entrySet()) {
            int pageId = entry.getKey();
            List<String> codes = entry.getValue();

            if (pageCache.containsKey(pageId)) {
                results.add(PageResult.hit(pageId, pageCache.get(pageId)));
            } else {
                results.add(PageResult.miss(pageId, codes));
            }
        }

        return results;
    }

    @Override
    public void put(List<T> data) {
        // 엔진 내부에서 pageId 분류
        for (T item : data) {
            double[] coords = coordExtractor.apply(item);
            int pageId = spatialIndex.toPageId(coords[0], coords[1]);
            pageCache.computeIfAbsent(pageId, k -> new ArrayList<>()).add(item);
        }
    }

    public long getCacheSize() {
        return pageCache.size();
    }

    public boolean isCached(int pageId) {
        return pageCache.containsKey(pageId);
    }
}