package minidb.index;

public class HilbertCurve {

    private static final int ORDER = 15;
    private static final long N = 1L << ORDER; // 32768

    private static final double MIN_LAT = 33.0;
    private static final double MAX_LAT = 38.5;
    private static final double MIN_LNG = 126.0;
    private static final double MAX_LNG = 129.5;

    /**
     * 좌표 → 힐버트값
     */
    public static long encode(double lat, double lng) {
        long x = toGrid(lng, MIN_LNG, MAX_LNG);
        long y = toGrid(lat, MIN_LAT, MAX_LAT);
        return xy2d(N, x, y);
    }

    /**
     * 격자 좌표 → 힐버트값
     *
     * Why: getPageIds에서 격자 순회 시 좌표 변환 없이 직접 힐버트값 계산
     * x, y는 0 ~ N-1 범위
     */
    public static long encodeGrid(long x, long y) {
        x = Math.max(0, Math.min(N - 1, x));
        y = Math.max(0, Math.min(N - 1, y));
        return xy2d(N, x, y);
    }

    /**
     * 좌표 → 격자 인덱스 (0 ~ N-1)
     * getPageIds에서 중심 격자 계산에 사용
     */
    public static long toGridX(double lng) {
        return toGrid(lng, MIN_LNG, MAX_LNG);
    }

    public static long toGridY(double lat) {
        return toGrid(lat, MIN_LAT, MAX_LAT);
    }

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