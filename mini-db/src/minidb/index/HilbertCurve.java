package minidb.index;

public class HilbertCurve {

    // ORDER=15 → 힐버트 값 최대 2^30 ≈ 10억 → long으로 충분
    private static final int ORDER = 15;
    private static final long N = 1L << ORDER; // 32768

    // 더미 데이터 좌표 범위 (DummyDataGenerator 기준)
    private static final double MIN_LAT = 33.0;
    private static final double MAX_LAT = 38.5;
    private static final double MIN_LNG = 126.0;
    private static final double MAX_LNG = 129.5;

    public static long encode(double lat, double lng) {
        long x = toGrid(lng, MIN_LNG, MAX_LNG);
        long y = toGrid(lat, MIN_LAT, MAX_LAT);
        return xy2d(N, x, y);
    }

    /**
     * 실수 좌표 → 정수 격자 (0 ~ N-1)
     *
     * 예: lng=127.0276, MIN=126.0, MAX=129.5
     *   ratio = (127.0276 - 126.0) / 3.5 ≈ 0.293
     *   grid  = 0.293 × 32767 ≈ 9614
     */
    private static long toGrid(double val, double min, double max) {
        double ratio = (val - min) / (max - min);
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        return (long)(ratio * (N - 1));
    }

    private static long xy2d(long n, long x, long y) {
        long d = 0;

        for (long s = n / 2; s > 0; s /= 2) {

            long rx = (x & s) > 0 ? 1 : 0;
            long ry = (y & s) > 0 ? 1 : 0;

            d += s * s * ((3 * rx) ^ ry);

            long[] next = rotate(s, x, y, rx, ry);
            x = next[0];
            y = next[1];
        }

        return d;
    }

    private static long[] rotate(long n, long x, long y, long rx, long ry) {
        if (ry == 0) {
            if (rx == 1) {
                x = n - 1 - x;
                y = n - 1 - y;
            }
            long temp = x;
            x = y;
            y = temp;
        }
        return new long[]{x, y};
    }
}