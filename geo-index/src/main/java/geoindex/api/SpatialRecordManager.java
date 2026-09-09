package geoindex.api;

import geoindex.buffer.CacheManager;
import geoindex.exception.CorruptedIndexException;
import geoindex.index.SpatialIndex;
import geoindex.metric.EngineMetrics;
import geoindex.storage.Page;
import geoindex.storage.PageLayout;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * 좌표 ↔ 페이지를 잇는 계층. overflow 체인 생성과 순회를 담당한다.
 *
 * 동시성 계약: put · search 는 여러 스레드에서 동시에 불러도 안전하다.
 * 같은 pageId 에 대한 쓰기는 pageLocks 로 직렬화되고, 읽기는 서로 동시에 통과한다.
 * writeRecord 가 recordCount 를 읽고 갱신하는 read-modify-write 라, 락이 없으면
 * 두 스레드가 같은 슬롯을 배정받아 레코드가 예외 없이 사라진다.
 *
 * 운영에서는 현재 rebuild 안의 순차 적재만 쓰기 경로를 타지만, 그 순차성은
 * 호출자(loader)의 성질이지 이 클래스의 전제가 아니다. put 은 public 이고
 * loader 도 호출자 코드다. 락을 걷어내려면 "적재는 단일 스레드" 를 계약으로
 * 명시해야 하고, 그러면 500 스레드 동시성 테스트도 함께 다시 써야 한다.
 *
 * 반면 flush 는 이 락에 참여하지 않는다. 이유는 CacheManager.flush 참고.
 */
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

    public void close() {
        cacheManager.close();
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
        Page page = cacheManager.getOrCreatePage(pageId);
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
                    return;
                }

                int overflowPageId = PageLayout.getOverflowPageId(current);
                if (overflowPageId == PageLayout.NO_OVERFLOW) {
                    overflowPageId = allocateOverflowPage();
                    PageLayout.setOverflowPageId(current, overflowPageId);
                }
                current = cacheManager.getOrCreatePage(overflowPageId);
            }
        } finally {
            writeLock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // 파일 기반 검색
    // -------------------------------------------------------------------------

    /** 운영 진입점. SpatialCacheEngine 이 부르는 것은 이 하나뿐이다. */
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

    /** pageId 를 이미 아는 경우. 워밍업과 테스트가 쓴다. */
    public List<String> getAllCodesByPageId(int pageId) {
        return readAllCodesFromChain(pageId);
    }

    /**
     * 벤치마크 전용. 운영은 병원 코드만 필요하지만 벤치마크는 Hospital.fromBytes 로
     * 레코드를 복원해야 해서 원본 바이트가 필요하다. 그래서 이 메서드만 반환 타입이 다르다.
     */
    public List<byte[]> searchRadius(double lat, double lng, double radiusKm) {
        List<Integer> pageIds = spatialIndex.getPageIds(lat, lng, radiusKm);
        List<byte[]> results = new ArrayList<>();

        for (int pageId : pageIds) {
            results.addAll(readAllRecordsFromChain(pageId));
        }
        return results;
    }

    /** searchRadiusCodesByPageId 를 평탄화한 것. pageId 별 묶음이 필요 없을 때 쓴다. */
    public List<String> searchRadiusCodes(double lat, double lng, double radiusKm) {
        List<String> codes = new ArrayList<>();
        searchRadiusCodesByPageId(lat, lng, radiusKm)
                .values().forEach(codes::addAll);
        return codes;
    }

    // -------------------------------------------------------------------------
    // overflow 체인 순회 (내부 공통 로직)
    // -------------------------------------------------------------------------

    /** 체인의 레코드를 병원 코드 문자열로 바꾼다. 순회는 readAllRecordsFromChain 이 한다. */
    private List<String> readAllCodesFromChain(int pageId) {
        List<byte[]> records = readAllRecordsFromChain(pageId);
        List<String> codes = new ArrayList<>(records.size());
        for (byte[] record : records) {
            codes.add(new String(record));
        }
        return codes;
    }

    /**
     * 체인 전체를 순회해 레코드를 원본 바이트로 모은다. 순회를 아는 코드는 여기뿐이다.
     *
     * 존재 확인이 getLock 보다 앞에 온다. 순서를 바꾸면 없는 칸마다 락 객체가 남고,
     * 그 맵을 비우는 곳도 rebuild 뿐이다. 디스크 읽기까지 락 밖에서 끝나 락 구간도 짧아진다.
     *
     * primary 락 하나가 체인 전체를 보호한다. 읽기든 쓰기든 반드시 primary 를 거쳐
     * 체인에 들어오므로, overflow 페이지는 자기 락을 따로 잡지 않는다.
     */
    private List<byte[]> readAllRecordsFromChain(int pageId) {
        Page page = cacheManager.findPage(pageId);
        if (page == null) return Collections.emptyList();

        ReentrantReadWriteLock.ReadLock readLock = getLock(pageId).readLock();
        readLock.lock();
        try {
            // 여기만 던지지 않는다: put 이 getOrCreatePage 를 락 밖에서 불러,
            // 처음 쓰이는 칸을 동시에 조회하면 초기화 전 페이지가 보인다 — 손상이 아니다.
            if (!PageLayout.isInitialized(page)) {
                return Collections.emptyList();
            }

            List<byte[]> records = new ArrayList<>(PageLayout.readAllRecords(page));

            int overflowPageId = PageLayout.getOverflowPageId(page);
            int hops = 0;
            while (overflowPageId != PageLayout.NO_OVERFLOW) {
                if (++hops > OVERFLOW_PAGES) {
                    throw new CorruptedIndexException(
                            "chain longer than the overflow pool: primary=" + pageId + " hops=" + hops, pageId);
                }
                Page overflowPage = cacheManager.findPage(overflowPageId);
                if (overflowPage == null) {
                    throw new CorruptedIndexException(
                            "chain points to a missing page: primary=" + pageId + " next=" + overflowPageId, pageId);
                }
                if (!PageLayout.isInitialized(overflowPage)) {
                    throw new CorruptedIndexException(
                            "chain page is not initialized: primary=" + pageId + " next=" + overflowPageId, pageId);
                }
                records.addAll(PageLayout.readAllRecords(overflowPage));
                overflowPageId = PageLayout.getOverflowPageId(overflowPage);
            }
            return records;
        } finally {
            readLock.unlock();
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

    public int getUsedPageCount() {
        return cacheManager.getUsedPageCount();
    }

}