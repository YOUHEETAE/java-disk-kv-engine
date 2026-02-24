package minidb.index;

import java.util.ArrayList;
import java.util.List;

public class GeoHashIndex implements SpatialIndex{
    private static final int PRECISION = 6;
    private static final int MAX_PAGE = 10000;

    @Override
    public int toPageId(double lat, double lng) {
        String geohash = GeoHash.toGeohash(lat, lng, PRECISION);
        long morton = GeoHash.toLong(geohash);
        return (int)(morton % MAX_PAGE);
    }

    // TODO: radiusKm에 따라 동적으로 셀 범위 조정
    @Override
    public List<Integer> getPageIds(double lat, double lng, double radiusKm) {
        String geohash = GeoHash.toGeohash(lat, lng, PRECISION);
        long morton = GeoHash.toLong(geohash);

        List<Long> neighbors = GeoHash.getNeighbors(morton);
        List<Integer> pageIds = new ArrayList<>();

        for(Long cell : neighbors){
            int pageId = (int) (cell % MAX_PAGE);
            if(!pageIds.contains(pageId)){
                pageIds.add(pageId);
            }
        }
        return pageIds;
    }
}
