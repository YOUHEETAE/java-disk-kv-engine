package minidb.test;

import minidb.index.GeoHash;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class GeohashTest {

    @Test
    void toGeohash() {
        String geohash = GeoHash.toGeohash(37.4979, 127.0276, 7);
        System.out.println("강남: " + geohash);
        assertNotNull(geohash);
        assertEquals(7, geohash.length());
    }
    @Test
    void toLong() {
        String geohash = GeoHash.toGeohash(37.4979, 127.0276, 7);
        long value = GeoHash.toLong(geohash);
        System.out.println("강남 Long : " + value);
        assertTrue(value > 0);
    }

    @Test
    void testSpatialLocality() {
        long gangnam  = GeoHash.toLong(GeoHash.toGeohash(37.4979, 127.0276, 7));
        long nearby   = GeoHash.toLong(GeoHash.toGeohash(37.4990, 127.0280, 7));
        long farAway  = GeoHash.toLong(GeoHash.toGeohash(35.1796, 129.0756, 7));

        System.out.println("강남:  " + gangnam);
        System.out.println("근처:  " + nearby);
        System.out.println("부산:  " + farAway);

        assertTrue(Math.abs(gangnam - nearby) < Math.abs(gangnam - farAway));
    }

    @Test
    void testInvalidGeohash() {
        assertThrows(IllegalArgumentException.class, () -> {
            GeoHash.toLong("invalid!");
        });
    }
    @Test
    void testNeighborRoundTrip() {
        long morton = GeoHash.toLong(GeoHash.toGeohash(37.4979, 127.0276, 6));
        long north  = GeoHash.neighbor(morton, 1, 0);
        long back   = GeoHash.neighbor(north, -1, 0);

        assertNotEquals(GeoHash.INVALID_CELL, north);
        assertEquals(morton, back);
    }

    @Test
    void testGetNeighbors() {
        long morton = GeoHash.toLong(GeoHash.toGeohash(37.4979, 127.0276, 6));
        List<Long> neighbors = GeoHash.getNeighbors(morton);

        System.out.println("이웃 셀 수: " + neighbors.size());

        assertTrue(neighbors.size() >= 1);
        assertTrue(neighbors.size() <= 9);

        assertTrue(neighbors.contains(morton));

        assertEquals(neighbors.size(), neighbors.stream().distinct().count());
    }
}
