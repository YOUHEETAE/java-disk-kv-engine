package geoindex.test;

import geoindex.index.GeoHash;
import geoindex.index.GeoHashIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeohashIndexTest {
    /** GeoHashIndex.BITS_PER_AXIS 와 같은 값. 운영과 다른 해상도를 검증하지 않기 위해 맞춘다. */
    static final int BITS_PER_AXIS = 15;

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
    /**
     * pageId 는 int 로 다뤄지므로 Morton 값이 int 양수 범위를 넘으면 안 된다.
     * Morton 비트 수 = BITS_PER_AXIS × 2 이므로, 축당 16비트 이상으로 올리면 여기서 걸린다.
     *
     * 기존 debug* 메서드들을 대체한다 — 값을 출력만 하고 아무것도 검증하지 않아
     * 회귀를 잡지 못했다. 실제로 그 자리에서 축당 17비트(precision 7)를 쓰고 있었다.
     */
    @Test
    void pageId가_int_양수_범위에_들어간다() {
        double[][] points = {
                {33.0, 124.0}, {33.0, 132.0}, {38.6, 124.0}, {38.6, 132.0},  // 한국 박스 네 꼭짓점
                {-90.0, -180.0}, {90.0, 180.0},                              // 좌표계 극단
        };

        for (double[] p : points) {
            long morton = GeoHash.toMorton(p[0], p[1], BITS_PER_AXIS);
            assertTrue(morton >= 0 && morton <= Integer.MAX_VALUE,
                    "Morton 이 int 범위를 벗어났다: " + morton + " @ " + p[0] + ", " + p[1]);
            assertTrue(index.toPageId(p[0], p[1]) >= 0,
                    "pageId 가 음수다 @ " + p[0] + ", " + p[1]);
        }
    }
}
