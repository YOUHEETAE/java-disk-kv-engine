package geoindex.index;

public class GeoHash {

    private static final int BITS = 15;


    public static long toMorton(double lat, double lng, int precision) {
        double minLat = -90,  maxLat = 90;
        double minLng = -180, maxLng = 180;

        long morton = 0;
        int totalBits = precision * 5;
        boolean isLng = true;

        for (int i = 0; i < totalBits; i++) {
            if (isLng) {
                double mid = (minLng + maxLng) / 2;
                if (lng >= mid) { morton = (morton << 1) | 1; minLng = mid; }
                else            { morton = (morton << 1);      maxLng = mid; }
            } else {
                double mid = (minLat + maxLat) / 2;
                if (lat >= mid) { morton = (morton << 1) | 1; minLat = mid; }
                else            { morton = (morton << 1);      maxLat = mid; }
            }
            isLng = !isLng;
        }
        return morton;
    }

    // latBits/lngBits → Morton 재조합
    public static long interleave(long lngBits, long latBits) {
        long morton = 0;
        for (int i = BITS - 1; i >= 0; i--) {
            morton = (morton << 1) | ((lngBits >> i) & 1);
            morton = (morton << 1) | ((latBits >> i) & 1);
        }
        return morton;
    }

}


