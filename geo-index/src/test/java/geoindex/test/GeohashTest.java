package geoindex.test;

import geoindex.index.GeoHash;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class GeohashTest {

    // -------------------------------------------------------------------------
    // toMorton
    // -------------------------------------------------------------------------

    @Test
    void toMorton_결정론적_같은좌표_같은값() {
        long a = GeoHash.toMorton(37.4979, 127.0276, 6);
        long b = GeoHash.toMorton(37.4979, 127.0276, 6);
        assertEquals(a, b);
    }

    @Test
    void toMorton_다른좌표_다른값() {
        long gangnam = GeoHash.toMorton(37.4979, 127.0276, 6);
        long busan   = GeoHash.toMorton(35.1796, 129.0756, 6);
        assertNotEquals(gangnam, busan);
    }

    @Test
    void toMorton_공간지역성_가까운좌표가_더_가까운값() {
        long gangnam = GeoHash.toMorton(37.4979, 127.0276, 6);
        long nearby  = GeoHash.toMorton(37.4990, 127.0280, 6);
        long busan   = GeoHash.toMorton(35.1796, 129.0756, 6);

        assertTrue(Math.abs(gangnam - nearby) < Math.abs(gangnam - busan));
    }

    @Test
    void toMorton_양수값_반환() {
        long morton = GeoHash.toMorton(37.4979, 127.0276, 6);
        assertTrue(morton > 0);
    }

    // -------------------------------------------------------------------------
    // interleave
    // -------------------------------------------------------------------------

    @Test
    void interleave_0_0은_0() {
        assertEquals(0L, GeoHash.interleave(0, 0));
    }

    @Test
    void interleave_lng1_lat0은_2() {
        // lngBit0=1, latBit0=0 → 마지막 두 비트: 10 → 2
        assertEquals(2L, GeoHash.interleave(1, 0));
    }

    @Test
    void interleave_lng0_lat1은_1() {
        // lngBit0=0, latBit0=1 → 마지막 두 비트: 01 → 1
        assertEquals(1L, GeoHash.interleave(0, 1));
    }
}
