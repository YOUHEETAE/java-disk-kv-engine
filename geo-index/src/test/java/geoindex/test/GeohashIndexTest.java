package geoindex.test;

import geoindex.index.GeoHash;
import geoindex.index.GeoHashIndex;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    void debugShift() {
        double lat = 37.420964;
        double lng = 127.126865;
        double radius = 5.0;

        double deltaDegreeY = radius / 110.0;
        double kmPerDegreeLon = 111.32 * Math.cos(Math.toRadians(lat));
        double deltaDegreeX = radius / kmPerDegreeLon;

        for (int precision : new int[]{6,7,8}){


        long[] corners = {
                GeoHash.toMorton(lat - deltaDegreeY, lng - deltaDegreeX, precision),
                GeoHash.toMorton(lat - deltaDegreeY, lng + deltaDegreeX, precision),
                GeoHash.toMorton(lat + deltaDegreeY, lng - deltaDegreeX, precision),
                GeoHash.toMorton(lat + deltaDegreeY, lng + deltaDegreeX, precision)
        };

        long minLngBits = Long.MAX_VALUE, maxLngBits = Long.MIN_VALUE;
        long minLatBits = Long.MAX_VALUE, maxLatBits = Long.MIN_VALUE;
        for (long morton : corners) {
            long[] bits = GeoHash.deinterleave(morton);
            minLngBits = Math.min(minLngBits, bits[0]);
            maxLngBits = Math.max(maxLngBits, bits[0]);
            minLatBits = Math.min(minLatBits, bits[1]);
            maxLatBits = Math.max(maxLatBits, bits[1]);
        }

        System.out.println("lat loop 횟수: " + (maxLatBits - minLatBits + 1));
        System.out.println("lng loop 횟수: " + (maxLngBits - minLngBits + 1));

        // 정규화 상수 (GeoHashIndex와 동일)
            long minMorton = GeoHash.toMorton(33.0, 124.0, precision);
            long maxMorton = GeoHash.toMorton(38.6, 132.0, precision);

        for (int maxPages : new int[]{512, 1024, 2048, 4096}) {
            long mortonRange = maxMorton - minMorton;
            Set<Integer> pageSet = new HashSet<>();
            int maxPageId = 0;

            for (long latBits = minLatBits; latBits <= maxLatBits; latBits++) {
                for (long lngBits = minLngBits; lngBits <= maxLngBits; lngBits++) {
                    long morton = GeoHash.interleave(lngBits, latBits);
                    long offset = Math.max(0, morton - minMorton);
                    int pageId = (int) Math.min(offset * maxPages / mortonRange, maxPages - 1);
                    pageSet.add(pageId);
                    maxPageId = Math.max(maxPageId, pageId);
                }
            }

            long dbSize = (long) (maxPages) * 4096 / 1024 / 1024;
            System.out.println("MAX_PAGES=" + maxPages
                    + " → pageIds=" + pageSet.size()
                    + " | max pageId=" + maxPageId
                    + " | db 크기=" + dbSize + "MB");
            System.out.println("PRECISION=" + precision + " MAX_PAGES=1024 → pageIds=...");
        }
        }
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
    void debugDeinterleave() {
        long morton1 = GeoHash.toMorton(37.37, 127.07, 6); // 좌하단
        long morton2 = GeoHash.toMorton(37.46, 127.18, 6); // 우상단

        long[] bits1 = GeoHash.deinterleave(morton1);
        long[] bits2 = GeoHash.deinterleave(morton2);

        System.out.println("morton1: " + Long.toBinaryString(morton1) + " (" + Long.toBinaryString(morton1).length() + "비트)");
        System.out.println("morton2: " + Long.toBinaryString(morton2) + " (" + Long.toBinaryString(morton2).length() + "비트)");
        System.out.println("bits1 lng=" + bits1[0] + " lat=" + bits1[1]);
        System.out.println("bits2 lng=" + bits2[0] + " lat=" + bits2[1]);
        System.out.println("latBits 차이: " + Math.abs(bits1[1] - bits2[1]));
        System.out.println("lngBits 차이: " + Math.abs(bits1[0] - bits2[0]));
    }
    @Test
    void debugInterleave() {
        long morton1 = GeoHash.toMorton(37.37, 127.07, 6);
        long[] bits = GeoHash.deinterleave(morton1);
        long reinterleaved = GeoHash.interleave(bits[0], bits[1]);

        System.out.println("원본    : " + Long.toBinaryString(morton1));
        System.out.println("재조합  : " + Long.toBinaryString(reinterleaved));
        System.out.println("같은가? : " + (morton1 == reinterleaved));

        // 인접 셀 interleave 결과
        long m1 = GeoHash.interleave(bits[0],     bits[1]);
        long m2 = GeoHash.interleave(bits[0] + 1, bits[1]);
        long m3 = GeoHash.interleave(bits[0],     bits[1] + 1);
        System.out.println(">> 20 원본     : " + (m1 >> 20));
        System.out.println(">> 20 lng+1    : " + (m2 >> 20));
        System.out.println(">> 20 lat+1    : " + (m3 >> 20));
    }
    @Test
    void debugInterleave7() {
        long m1 = GeoHash.toMorton(37.37, 127.07, 7);
        long[] bits = GeoHash.deinterleave(m1);

        long ma = GeoHash.interleave(bits[0],     bits[1]);
        long mb = GeoHash.interleave(bits[0] + 1, bits[1]);
        long mc = GeoHash.interleave(bits[0],     bits[1] + 1);
        System.out.println(">> 20 원본  : " + (ma >> 20));
        System.out.println(">> 20 lng+1 : " + (mb >> 20));
        System.out.println(">> 20 lat+1 : " + (mc >> 20));
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