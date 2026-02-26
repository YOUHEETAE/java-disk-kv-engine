package minidb.test;

import minidb.index.GeoHashIndex;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GeohashIndexTest {

    GeoHashIndex index = new GeoHashIndex();

    @Test
    void testToPageId() {
        int pageId = index.toPageId(37.4979, 127.0276);
        System.out.println("강남 pageId: " + pageId);
        assertTrue(pageId >= 0 && pageId < 10000);
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
}