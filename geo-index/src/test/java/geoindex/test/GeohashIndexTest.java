package geoindex.test;

import geoindex.index.GeoHash;
import geoindex.index.GeoHashIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeohashIndexTest {
    static final int PRECISION = 7;

    GeoHashIndex index = new GeoHashIndex();


    @Test
    void testToPageId() {
        int pageId = index.toPageId(37.4979, 127.0276);
        System.out.println("강남 pageId: " + pageId);
        assertTrue(pageId >= 0);
    }

    @Test
    void testSameLocationSamePageId() {
        int pageId1 = index.toPageId(37.4979, 127.0276);
        int pageId2 = index.toPageId(37.4979, 127.0276);
        assertEquals(pageId1, pageId2);
    }

    @Test
    void testGetPageIds() {
        List<Integer> pageIds = index.getPageIds(37.4979, 127.0276, 5.0);
        System.out.println("반환 페이지 수: " + pageIds.size());
        assertTrue(pageIds.size() >= 1);
    }

    @Test
    void testNearbyLocationSameOrAdjacentPage() {
        int gangnam = index.toPageId(37.4979, 127.0276);
        List<Integer> pageIds = index.getPageIds(37.4979, 127.0276, 5.0);
        assertTrue(pageIds.contains(gangnam));
    }
    @Test
    void debugPageIds() {
        GeoHashIndex index = new GeoHashIndex();
        double lat = 37.420964;
        double lng = 127.126865;
        double radius = 5.0;

        List<Integer> pageIds = index.getPageIds(lat, lng, radius);
        System.out.println("=== pageId 분포 ===");
        System.out.println("총 pageIds 수: " + pageIds.size());
        System.out.println("min pageId: " + pageIds.stream().mapToInt(i->i).min().getAsInt());
        System.out.println("max pageId: " + pageIds.stream().mapToInt(i->i).max().getAsInt());
        System.out.println("pageId 범위: " + (pageIds.stream().mapToInt(i->i).max().getAsInt()
                - pageIds.stream().mapToInt(i->i).min().getAsInt()));
    }
    @Test
    void debugMorton() {
        long morton = GeoHash.toMorton(37.420964, 127.126865, 7);
        System.out.println("morton: " + morton);
        System.out.println("morton 2진수: " + Long.toBinaryString(morton));
        System.out.println("morton 비트 수: " + Long.toBinaryString(morton).length());
        System.out.println(">> 20: " + (morton >> 20));
    }

    @Test
    void debugBitDiff() {
        long m1 = GeoHash.toMorton(37.37, 127.07, 7);
        long m2 = GeoHash.toMorton(37.46, 127.18, 7);

        System.out.println("m1: " + Long.toBinaryString(m1));
        System.out.println("m2: " + Long.toBinaryString(m2));

        // 몇 번째 비트부터 차이나는지
        for (int i = 34; i >= 0; i--) {
            long bit1 = (m1 >> i) & 1;
            long bit2 = (m2 >> i) & 1;
            if (bit1 != bit2) {
                System.out.println("처음 차이나는 비트: " + i + "번째");
                break;
            }
        }
    }
    @Test
    void debugMortonRange() {
        for (int precision : new int[]{6, 7, 8}) {
            long minMorton = GeoHash.toMorton(33.0, 124.0, precision);
            long maxMorton = GeoHash.toMorton(38.6, 132.0, precision);
            System.out.println("PRECISION=" + precision
                    + " minMorton=" + minMorton
                    + " maxMorton=" + maxMorton
                    + " range=" + (maxMorton - minMorton));
        }
    }
}