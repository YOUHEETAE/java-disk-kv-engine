package geoindex.api;

import geoindex.buffer.CacheManager;
import geoindex.cache.SpatialCacheEngine;
import geoindex.index.SpatialIndex;
import geoindex.storage.Page;
import geoindex.storage.PageLayout;

import java.util.*;
import java.util.function.Consumer;

/**
 * 최상단 API 레이어
 *
 * 책임:
 *   - put(): 병원 코드를 파일에 저장
 *   - search(): pageId 조회 → cacheEngine.getOrMiss() 호출 → HIT/MISS 반환
 *   - rebuild(): 임시 파일 구축 → atomic rename → JVM 캐시 초기화
 *
 * 의존성:
 *   SpatialRecordManager → CacheManager    (버퍼 레이어)
 *   SpatialRecordManager → SpatialIndex    (pageId 변환)
 *   SpatialRecordManager → SpatialCacheEngine (HIT/MISS 판단 위임)
 *   SpatialCacheEngine → SpatialRecordManager 없음 (순환 참조 없음)
 */
public class SpatialRecordManager {

    private static final int PRIMARY_PAGES  = 32_768;
    private static final int OVERFLOW_PAGES = 40_960;
    private static final int TOTAL_PAGES    = PRIMARY_PAGES + OVERFLOW_PAGES;

    private final CacheManager cacheManager;
    private final SpatialIndex spatialIndex;
    private final SpatialCacheEngine<?> cacheEngine;
    private Deque<Integer> overflowFreeList;

    // 캐시 엔진 없이 사용할 때 (테스트 등)
    public SpatialRecordManager(CacheManager cacheManager, SpatialIndex spatialIndex) {
        this(cacheManager, spatialIndex, null);
    }

    public SpatialRecordManager(CacheManager cacheManager, SpatialIndex spatialIndex,
                                SpatialCacheEngine<?> cacheEngine) {
        this.cacheManager = cacheManager;
        this.spatialIndex = spatialIndex;
        this.cacheEngine = cacheEngine;
        this.overflowFreeList = buildFreeList();
    }

    // -------------------------------------------------------------------------
    // search() — pageId 조회 + HIT/MISS 판단 위임
    // -------------------------------------------------------------------------

    public <T> List<PageResult<T>> search(double lat, double lng, double radiusKm) {
        Map<Integer, List<String>> codesByPageId = searchRadiusCodesByPageId(lat, lng, radiusKm);
        List<PageResult<T>> results = new ArrayList<>();

        for (Map.Entry<Integer, List<String>> entry : codesByPageId.entrySet()) {
            int pageId = entry.getKey();
            List<String> codes = entry.getValue();

            if (cacheEngine == null) {
                results.add(PageResult.miss(pageId, codes));
            } else {
                @SuppressWarnings("unchecked")
                SpatialCacheEngine<T> engine = (SpatialCacheEngine<T>) cacheEngine;
                results.add(engine.getOrMiss(pageId, codes));
            }
        }

        return results;
    }

    public <T> void putCache(int pageId, List<T> data) {
        if (cacheEngine == null) return;
        @SuppressWarnings("unchecked")
        SpatialCacheEngine<T> engine = (SpatialCacheEngine<T>) cacheEngine;
        engine.put(pageId, data);
    }

    // -------------------------------------------------------------------------
    // rebuild() — 임시 파일 구축 → atomic rename → JVM 캐시 초기화
    // -------------------------------------------------------------------------

    /**
     * 파일 재구축 + JVM 캐시 초기화
     *
     * rename 전까지 기존 파일 살아있음 → 요청 중단 없음
     *
     * Spring 사용 예:
     *   spatialRecordManager.rebuild(srm ->
     *       hospitalRepo.findAllCodes().forEach(h ->
     *           srm.put(h.getLat(), h.getLng(), h.getCode().getBytes())
     *       )
     *   );
     */
    public void rebuild(Consumer<SpatialRecordManager> loader) {
        cacheManager.rebuild(tempCm -> {
            SpatialRecordManager tempSrm = new SpatialRecordManager(tempCm, spatialIndex);
            loader.accept(tempSrm);
        });

        // rename 완료 후 JVM 캐시 초기화
        if (cacheEngine != null) {
            cacheEngine.clearCache();
        }
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

    // -------------------------------------------------------------------------
    // 파일 기반 검색 (내부 + Spring 직접 호출용)
    // -------------------------------------------------------------------------

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

            if (!codes.isEmpty()) result.put(pageId, codes);
        }

        return result;
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

    // -------------------------------------------------------------------------
    // 유틸
    // -------------------------------------------------------------------------

    private int allocateOverflowPage() {
        if (overflowFreeList.isEmpty()) {
            throw new IllegalStateException("overflow page pool exhausted");
        }
        return overflowFreeList.pop();
    }

    private static Deque<Integer> buildFreeList() {
        Deque<Integer> freeList = new ArrayDeque<>();
        for (int i = PRIMARY_PAGES; i < TOTAL_PAGES; i++) {
            freeList.push(i);
        }
        return freeList;
    }
}