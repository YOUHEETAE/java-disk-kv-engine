package geoindex.api;

import geoindex.buffer.CacheManager;
import geoindex.index.SpatialIndex;
import geoindex.storage.Page;
import geoindex.storage.PageLayout;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;

public class SpatialRecordManager {

    private static final int PRIMARY_PAGES  = 32_768;
    private static final int OVERFLOW_PAGES = 40_960;
    private static final int TOTAL_PAGES    = PRIMARY_PAGES + OVERFLOW_PAGES;

    private final CacheManager cacheManager;
    private final SpatialIndex spatialIndex;
    private ConcurrentLinkedDeque<Integer> overflowFreeList;

    public SpatialRecordManager(CacheManager cacheManager, SpatialIndex spatialIndex) {
        this.cacheManager = cacheManager;
        this.spatialIndex = spatialIndex;
        this.overflowFreeList = buildFreeList();
    }

    // -------------------------------------------------------------------------
    // put()
    // -------------------------------------------------------------------------

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
        synchronized (page) {
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
    }

    // -------------------------------------------------------------------------
    // 파일 기반 검색
    // -------------------------------------------------------------------------

    public Map<Integer, List<String>> searchRadiusCodesByPageId(double lat, double lng, double radiusKm) {
        List<Integer> pageIds = spatialIndex.getPageIds(lat, lng, radiusKm);
        Map<Integer, List<String>> result = new LinkedHashMap<>();

        for (int pageId : pageIds) {
            List<String> codes = readAllCodesFromChain(pageId);
            if (!codes.isEmpty()) result.put(pageId, codes);
        }

        return result;
    }

    public List<String> getAllCodesByPageId(int pageId) {
        return readAllCodesFromChain(pageId);
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

    public List<String> searchRadiusCodes(double lat, double lng, double radiusKm) {
        List<String> codes = new ArrayList<>();
        searchRadiusCodesByPageId(lat, lng, radiusKm)
                .values().forEach(codes::addAll);
        return codes;
    }

    // -------------------------------------------------------------------------
    // overflow 체인 순회 (내부 공통 로직)
    // -------------------------------------------------------------------------

    private List<String> readAllCodesFromChain(int pageId) {
        Page page = cacheManager.getPage(pageId);
        synchronized (page) {
            if (!PageLayout.isInitialized(page)) return Collections.emptyList();

            List<String> codes = new ArrayList<>();
            collectCodes(page, codes);

            int overflowPageId = PageLayout.getOverflowPageId(page);
            while (overflowPageId != PageLayout.NO_OVERFLOW) {
                Page overflowPage = cacheManager.getPage(overflowPageId);
                synchronized (overflowPage) {
                    if (!PageLayout.isInitialized(overflowPage)) break;
                    collectCodes(overflowPage, codes);
                    overflowPageId = PageLayout.getOverflowPageId(overflowPage);
                }
            }

            return codes;
        }
    }
    private void collectCodes(Page page, List<String> codes) {
        for (byte[] bytes : PageLayout.readAllRecords(page)) {
            codes.add(new String(bytes));
        }
    }

    // -------------------------------------------------------------------------
    // rebuild
    // -------------------------------------------------------------------------

    public void rebuild(Consumer<SpatialRecordManager> loader) {
        cacheManager.rebuild(tempCm -> {
            SpatialRecordManager tempSrm = new SpatialRecordManager(tempCm, spatialIndex);
            loader.accept(tempSrm);
        });
        this.overflowFreeList = buildFreeList();
    }

    // -------------------------------------------------------------------------
    // 유틸
    // -------------------------------------------------------------------------

    private int allocateOverflowPage() {
        Integer pageId = overflowFreeList.poll();
        if (pageId == null) {
            throw new IllegalStateException("overflow page pool exhausted");
        }
        return pageId;
    }

    private static ConcurrentLinkedDeque<Integer> buildFreeList() {
        ConcurrentLinkedDeque<Integer> freeList = new ConcurrentLinkedDeque<>();
        for (int i = PRIMARY_PAGES; i < TOTAL_PAGES; i++) {
            freeList.push(i);
        }
        return freeList;
    }
}
