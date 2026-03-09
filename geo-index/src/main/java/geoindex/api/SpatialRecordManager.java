package geoindex.api;

import geoindex.buffer.CacheManager;
import geoindex.index.SpatialIndex;
import geoindex.storage.Page;
import geoindex.storage.PageLayout;

import java.util.*;

public class SpatialRecordManager {

    private static final int PRIMARY_PAGES = 32768;
    private static final int OVERFLOW_PAGES = 40960;
    private static final int TOTAL_PAGES = PRIMARY_PAGES + OVERFLOW_PAGES;

    private final CacheManager cacheManager;
    private final SpatialIndex spatialIndex;
    private final Deque<Integer> overflowFreeList = new ArrayDeque<>();

    public SpatialRecordManager(CacheManager cacheManager, SpatialIndex spatialIndex) {
        this.cacheManager = cacheManager;
        this.spatialIndex = spatialIndex;


        for (int i = PRIMARY_PAGES; i < TOTAL_PAGES; i++) {
            overflowFreeList.push(i);
        }
    }

    public void put(double lat, double lng, byte[] value) {
        int pageId = spatialIndex.toPageId(lat, lng);
        Page page = cacheManager.getPage(pageId);

        if (!PageLayout.isInitialized(page)) {
            PageLayout.initializePage(page);
        }

        writeWithOverflow(page, value);
        cacheManager.putPage(page);
    }

    private void writeWithOverflow(Page page, byte[] value) {
        int slotId = PageLayout.writeRecord(page, value);

        if (slotId == -1) {
            int overflowPageId = PageLayout.getOverflowPageId(page);
            if (overflowPageId == PageLayout.NO_OVERFLOW) {
                overflowPageId = allocateOverflowPage();
                PageLayout.setOverflowPageId(page, overflowPageId);
            }
            Page overflowPage = cacheManager.getPage(overflowPageId);
            if (!PageLayout.isInitialized(overflowPage)) {
                PageLayout.initializePage(overflowPage);
            }
            writeWithOverflow(overflowPage, value);
            cacheManager.putPage(overflowPage);
        }
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

    public Map<Integer, List<String>> searchRadiusCodesByPageId(double lat, double lng, double radiusKm) {
        List<Integer> pageIds = spatialIndex.getPageIds(lat, lng, radiusKm);
        Map<Integer, List<String>> result = new LinkedHashMap<>();

        for (int pageId : pageIds) {
            Page page = cacheManager.getPage(pageId);
            if (!PageLayout.isInitialized(page)) continue;

            List<String> codes = new ArrayList<>();

            for (byte[] bytes : PageLayout.readAllRecords(page)) {
                codes.add(new String(bytes));
            }

            int overflowPageId = PageLayout.getOverflowPageId(page);
            while (overflowPageId != PageLayout.NO_OVERFLOW) {
                Page overflowPage = cacheManager.getPage(overflowPageId);
                if (!PageLayout.isInitialized(overflowPage)) break;
                for (byte[] bytes : PageLayout.readAllRecords(overflowPage)) {
                    codes.add(new String(bytes));
                }
                overflowPageId = PageLayout.getOverflowPageId(overflowPage);
            }

            if (!codes.isEmpty()) {
                result.put(pageId, codes);
            }
        }

        return result;
    }

    public List<String> getAllCodesByPageId(int pageId) {
        Page page = cacheManager.getPage(pageId);
        if (!PageLayout.isInitialized(page)) return Collections.emptyList();

        List<String> codes = new ArrayList<>();

        for (byte[] bytes : PageLayout.readAllRecords(page)) {
            codes.add(new String(bytes));
        }

        int overflowPageId = PageLayout.getOverflowPageId(page);
        while (overflowPageId != PageLayout.NO_OVERFLOW) {
            Page overflowPage = cacheManager.getPage(overflowPageId);
            if (!PageLayout.isInitialized(overflowPage)) break;
            for (byte[] bytes : PageLayout.readAllRecords(overflowPage)) {
                codes.add(new String(bytes));
            }
            overflowPageId = PageLayout.getOverflowPageId(overflowPage);
        }

        return codes;
    }

    public List<String> searchRadiusCodes(double lat, double lng, double radiusKm) {
        List<byte[]> results = searchRadius(lat, lng, radiusKm);
        List<String> codes = new ArrayList<>();
        for (byte[] bytes : results) {
            codes.add(new String(bytes));
        }
        return codes;
    }

    private int allocateOverflowPage() {
        if (overflowFreeList.isEmpty()) {
            throw new IllegalStateException("overflow page pool exhausted");
        }
        return overflowFreeList.pop();
    }
}