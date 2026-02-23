package minidb.index;

import java.util.ArrayList;
import java.util.List;

public class GeoHash {

    private final static String base32 = "0123456789bcdefghjkmnpqrstuvwxyz";
    private static final int BITS = 15;
    private static final int MASK = 0x7FFF; // 15bit = 32767
    public static final long INVALID_CELL = -1L;

    public static long toLong(String geohash) {
        long result = 0L;
        for(char c : geohash.toCharArray()) {
            int value = base32.indexOf(c);
            if(value == -1) {
                throw new IllegalArgumentException("Invalid geohash: " + c);
            }
            result = (result << 5) | value;
        }
        return result;
    }
    public static String toGeohash(double lat, double lng, int precision) {
        double minLat = -90,  maxLat = 90;
        double minLng = -180, maxLng = 180;

        StringBuilder geohash = new StringBuilder();
        int bits = 0;
        int bitCount = 0;
        boolean isLng = true;

        while (geohash.length() < precision) {
            if (isLng) {
                double mid = (minLng + maxLng) / 2;
                if (lng >= mid) { bits = (bits << 1) | 1; minLng = mid; }
                else            { bits = (bits << 1);      maxLng = mid; }
            } else {
                double mid = (minLat + maxLat) / 2;
                if (lat >= mid) { bits = (bits << 1) | 1; minLat = mid; }
                else            { bits = (bits << 1);      maxLat = mid; }
            }

            isLng = !isLng;
            bitCount++;

            if (bitCount == 5) {
                geohash.append(base32.charAt(bits));
                bits = 0;
                bitCount = 0;
            }
        }
        return geohash.toString();
    }


    public static long neighbor(long morton, int dLat, int dLng) {
        long lngBits = 0;
        long latBits = 0;

        for (int i = 29; i >= 0; i--) {
            long bit = (morton >> i) & 1;
            if ((29 - i) % 2 == 0)
                lngBits = (lngBits << 1) | bit;
            else
                latBits = (latBits << 1) | bit;
        }

        lngBits += dLng;
        latBits += dLat;

        if (lngBits < 0 || lngBits > MASK ||
                latBits < 0 || latBits > MASK)
            return INVALID_CELL;

        long result = 0;
        for (int i = BITS - 1; i >= 0; i--) {
            result <<= 1;
            result |= (lngBits >> i) & 1;
            result <<= 1;
            result |= (latBits >> i) & 1;
        }
        return result;
    }

    public static List<Long> getNeighbors(long morton) {
        List<Long> cells = new ArrayList<>();

        cells.add(morton);

        int[][] directions = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };

        for (int[] d : directions) {
            long n = neighbor(morton, d[0], d[1]);
            if (n != INVALID_CELL) cells.add(n);
        }

        return cells;
    }

}


