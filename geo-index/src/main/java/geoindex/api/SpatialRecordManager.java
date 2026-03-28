package geoindex.api;

import geoindex.buffer.CacheManager;
import geoindex.index.SpatialIndex;
import geoindex.metric.EngineMetrics;
import geoindex.storage.Page;
import geoindex.storage.PageLayout;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

public class SpatialRecordManager {

    private static final int PRIMARY_PAGES  = 32_768;
    private static final int OVERFLOW_PAGES = 40_960;
    private static final int TOTAL_PAGES    = PRIMARY_PAGES + OVERFLOW_PAGES;

    private final CacheManager cacheManager;
    private final SpatialIndex spatialIndex;
    private final EngineMetrics engineMetrics;
    private ConcurrentLinkedDeque<Integer> overflowFreeList;
    private final ConcurrentHashMap<Integer, ReentrantReadWriteLock> pageLocks;

    public SpatialRecordManager(CacheManager cacheManager, SpatialIndex spatialIndex,  EngineMetrics engineMetrics) {
        this.cacheManager = cacheManager;
        this.spatialIndex = spatialIndex;
        this.overflowFreeList = buildFreeList();
        this.pageLocks = new ConcurrentHashMap<>();
        this.engineMetrics = engineMetrics;
    }



    // -------------------------------------------------------------------------
    // 락 관리
    // -------------------------------------------------------------------------

    private ReentrantReadWriteLock getLock(int pageId) {
        return pageLocks.computeIfAbsent(pageId, k -> new ReentrantReadWriteLock());
    }

    // -------------------------------------------------------------------------
    // put()
    // -------------------------------------------------------------------------

    public void put(double lat, double lng, byte[] value) {
        int pageId = spatialIndex.toPageId(lat, lng);
        Page page = cacheManager.getPage(pageId);
        writeWithOverflow(page, value);
    }

    private void writeWithOverflow(Page page, byte[] value) {
        // primaryPage 락 하나로 전체 체인 보호
        int primaryPageId = page.getPageId();
        ReentrantReadWriteLock.WriteLock writeLock = getLock(primaryPageId).writeLock();
        writeLock.lock();
        try {
            Page current = page;
            while (true) {
                if (!PageLayout.isInitialized(current)) {
                    PageLayout.initializePage(current);
                }

                int slotId = PageLayout.writeRecord(current, value);

                if (slotId != -1) {
                    cacheManager.putPage(current);
                    return;
                }

                int overflowPageId = PageLayout.getOverflowPageId(current);
                if (overflowPageId == PageLayout.NO_OVERFLOW) {
                    overflowPageId = allocateOverflowPage();
                    PageLayout.setOverflowPageId(current, overflowPageId);
                }
                cacheManager.putPage(current);
                current = cacheManager.getPage(overflowPageId);
            }
        } finally {
            writeLock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // 파일 기반 검색
    // -------------------------------------------------------------------------

    public Map<Integer, List<String>> searchRadiusCodesByPageId(double lat, double lng, double radiusKm) {
        engineMetrics.incrementQueryCount();
        List<Integer> pageIds = spatialIndex.getPageIds(lat, lng, radiusKm);
        Map<Integer, List<String>> result = new LinkedHashMap<>();

        for (int pageId : pageIds) {
            List<String> codes = readAllCodesFromChain(pageId);
            if (!codes.isEmpty()) result.put(pageId, codes);
        }
        engineMetrics.addPageIds(result.size());
        return result;
    }

    public List<String> getAllCodesByPageId(int pageId) {
        return readAllCodesFromChain(pageId);
    }

    public List<byte[]> searchRadius(double lat, double lng, double radiusKm) {
        List<Integer> pageIds = spatialIndex.getPageIds(lat, lng, radiusKm);
        List<byte[]> results = new ArrayList<>();

        for (int pageId : pageIds) {
            ReentrantReadWriteLock.ReadLock readLock = getLock(pageId).readLock();
            readLock.lock();
            try {
                Page page = cacheManager.getPage(pageId);
                if (!PageLayout.isInitialized(page)) continue;

                results.addAll(PageLayout.readAllRecords(page));

                int overflowPageId = PageLayout.getOverflowPageId(page);
                while (overflowPageId != PageLayout.NO_OVERFLOW) {
                    // overflow 페이지는 별도 락 없이 읽음
                    // primaryPage 락이 전체 체인을 보호
                    Page overflowPage = cacheManager.getPage(overflowPageId);
                    if (!PageLayout.isInitialized(overflowPage)) break;
                    results.addAll(PageLayout.readAllRecords(overflowPage));
                    overflowPageId = PageLayout.getOverflowPageId(overflowPage);
                }
            } finally {
                readLock.unlock();
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
        // primaryPage 락 하나로 전체 체인 보호
        ReentrantReadWriteLock.ReadLock readLock = getLock(pageId).readLock();
        readLock.lock();
        try {
            Page page = cacheManager.getPage(pageId);
            if (!PageLayout.isInitialized(page)) return Collections.emptyList();

            List<String> codes = new ArrayList<>();
            collectCodes(page, codes);

            int overflowPageId = PageLayout.getOverflowPageId(page);
            while (overflowPageId != PageLayout.NO_OVERFLOW) {
                // overflow 페이지는 별도 락 없이 읽음
                // primaryPage 락이 전체 체인을 보호
                Page overflowPage = cacheManager.getPage(overflowPageId);
                if (!PageLayout.isInitialized(overflowPage)) break;
                collectCodes(overflowPage, codes);
                overflowPageId = PageLayout.getOverflowPageId(overflowPage);
            }

            return codes;
        } finally {
            readLock.unlock();
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
            SpatialRecordManager tempSrm = new SpatialRecordManager(tempCm, spatialIndex, engineMetrics);
            loader.accept(tempSrm);
        });
        this.overflowFreeList = buildFreeList();
        this.pageLocks.clear();
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

    // -------------------------------------------------------------------------
    // 메트릭
    // -------------------------------------------------------------------------

    public int getDirtyPageCount() {
        return cacheManager.getDirtyPageCount();
    }

    public int getUsedOverflowPageCount() {
        return OVERFLOW_PAGES - overflowFreeList.size();
    }
}