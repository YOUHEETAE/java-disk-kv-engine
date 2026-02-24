package minidb.api;

import minidb.buffer.CacheManager;
import minidb.index.SpatialIndex;
import minidb.storage.Page;
import minidb.storage.PageLayout;

import java.util.ArrayList;
import java.util.List;

public class SpatialRecordManager {

    private static final int MAX_PAGES = 10000;
    private final CacheManager cacheManager;
    private final SpatialIndex spatialIndex;

    public SpatialRecordManager(CacheManager cacheManager, SpatialIndex spatialIndex) {
        this.cacheManager = cacheManager;
        this.spatialIndex = spatialIndex;
    }

    public void put(double lat, double lng, byte[] value) {
        int pageId = spatialIndex.toPageId(lat, lng);
        Page page = cacheManager.getPage(pageId);

        if (!PageLayout.isInitialized(page)) {
            PageLayout.initializePage(page);
        }

        int slotId = PageLayout.writeRecord(page, value);

        if (slotId == -1) {
            int overflowPageId = PageLayout.getOverflowPageId(page);
            if (overflowPageId == PageLayout.NO_OVERFLOW) {
                overflowPageId = allocateNewPage();
                PageLayout.setOverflowPageId(page, overflowPageId);
            }
            Page overflowPage = cacheManager.getPage(overflowPageId);
            if (!PageLayout.isInitialized(overflowPage)) {
                PageLayout.initializePage(overflowPage);
            }
            PageLayout.writeRecord(overflowPage, value);
            cacheManager.putPage(overflowPage);
        }

        cacheManager.putPage(page);
    }

    public List<byte[]> searchRadius(double lat, double lng, double radiusKm) {
        List<Integer> pageIds = spatialIndex.getPageIds(lat, lng, radiusKm);
        List<byte[]> results = new ArrayList<>();

        for (int pageId : pageIds) {
            Page page = cacheManager.getPage(pageId);
            if (!PageLayout.isInitialized(page)) continue;

            results.addAll(PageLayout.readAllRecords(page));

            int overflowPageId = PageLayout.getOverflowPageId(page);
            while (overflowPageId != PageLayout.NO_OVERFLOW) {
                Page overflowPage = cacheManager.getPage(overflowPageId);
                if (!PageLayout.isInitialized(overflowPage)) break;
                results.addAll(PageLayout.readAllRecords(overflowPage));
                overflowPageId = PageLayout.getOverflowPageId(overflowPage);
            }
        }

        return results;
    }

    private int allocateNewPage() {
        for (int pageId = 0; pageId < MAX_PAGES; pageId++) {
            Page page = cacheManager.getPage(pageId);
            if (!PageLayout.isInitialized(page)) return pageId;
        }
        throw new IllegalStateException("no available pages");
    }
}