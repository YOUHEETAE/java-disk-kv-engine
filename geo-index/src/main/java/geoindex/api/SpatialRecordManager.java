package geoindex.api;

import geoindex.buffer.CacheManager;
import geoindex.exception.CorruptedIndexException;
import geoindex.index.SpatialIndex;
import geoindex.metric.EngineMetrics;
import geoindex.storage.Page;
import geoindex.storage.PageLayout;

import java.nio.charset.StandardCharsets;
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

    /**
     * pageId 별 락. 쓰기와 읽기가 공유하는 유일한 지점이다.
     *
     * 키가 primary pageId 라 락 하나가 체인 전체를 덮는다. 그래서 조회한 칸마다
     * 락 객체가 하나씩 생기고, 이 맵을 비우는 곳은 rebuild 뿐이다 —
     * 읽기 경로가 존재 확인을 이 호출보다 먼저 하는 이유다.
     */
    private ReentrantReadWriteLock getLock(int pageId) {
        return pageLocks.computeIfAbsent(pageId, k -> new ReentrantReadWriteLock());
    }

    public void put(double lat, double lng, byte[] value) {
        if (value.length > PageLayout.MAX_RECORD_SIZE) {
            throw new IllegalArgumentException(
                    "record too large: " + value.length + " > " + PageLayout.MAX_RECORD_SIZE);
        }
        int pageId = spatialIndex.toPageId(lat, lng);
        writeWithOverflow(pageId, value);
    }

    /**
     * 체인 끝까지 내려가며 레코드를 붙인다. 필요하면 overflow 페이지를 새로 단다.
     *
     * 락이 이 메서드에 있는 이유: 원자 단위가 "체인에 레코드 하나를 붙인다"이고,
     * 그것이 여러 페이지와 여러 PageLayout 호출에 걸쳐 있다. 아래쪽(writeRecord 등)에
     * 걸면 호출 하나하나는 안전해지지만 "꽉 찼나 확인 → overflow 할당 → 링크 설정"
     * 순서가 원자적이지 않아, 두 스레드가 링크를 두 번 걸거나 서로를 덮는다.
     * PageLayout 은 static 유틸이라 잠글 대상도 없고 체인이라는 개념도 모른다.
     *
     * 페이지 획득도 락 안에 있다. 밖에 두면 getOrCreatePage 가 초기화되지 않은 빈
     * 페이지를 캐시에 넣은 뒤 락을 잡기 전까지 창이 생겨, 처음 쓰이는 칸을 동시에
     * 조회한 독자가 그 페이지를 본다. 대가로 페이지 획득이 락 구간에 들어오는데,
     * rebuild 적재 중에는 savePage 가 flush 때 한 번에 돌아 pageMap 이 계속 비어
     * 있으므로 디스크는 읽지 않는다. flush 뒤에 put 이 오면 락 안에서 읽는다.
     */
    private void writeWithOverflow(int primaryPageId, byte[] value) {
        ReentrantReadWriteLock.WriteLock writeLock = getLock(primaryPageId).writeLock();
        writeLock.lock();
        try {
            Page current = cacheManager.getOrCreatePage(primaryPageId);
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

    /**
     * overflow 페이지 번호를 하나 꺼낸다. 반납하는 곳은 없다.
     *
     * 그래서 소진되면 rebuild 전까지 회복되지 않는다. put 이 레코드 크기를 먼저
     * 검사하는 이유이기도 하다 — 한 페이지에 담을 수 없는 값은 이 풀을 다 태운다.
     */
    private int allocateOverflowPage() {
        Integer pageId = overflowFreeList.poll();
        if (pageId == null) {
            throw new IllegalStateException("overflow page pool exhausted");
        }
        return pageId;
    }

    /**
     * overflow 번호 공간을 PRIMARY_PAGES 부터 시작한다.
     *
     * primary 는 Morton 코드라 0 ~ 2^30 을 쓰므로 이 구간과 겹친다. 한국 좌표는
     * 상위 비트가 커서 부딪히지 않을 뿐, 번호 공간이 분리돼 있지는 않다.
     * 근본 해결은 번호를 (primary, 체인 순번)에서 계산하는 것이고 그러면 이 목록도 사라진다.
     */
    private static ConcurrentLinkedDeque<Integer> buildFreeList() {
        ConcurrentLinkedDeque<Integer> freeList = new ConcurrentLinkedDeque<>();
        for (int i = PRIMARY_PAGES; i < TOTAL_PAGES; i++) {
            freeList.push(i);
        }
        return freeList;
    }

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

    /** 체인의 레코드를 병원 코드 문자열로 바꾼다. 순회는 readAllRecordsFromChain 이 한다. */
    private List<String> readAllCodesFromChain(int pageId) {
        List<byte[]> records = readAllRecordsFromChain(pageId);
        List<String> codes = new ArrayList<>(records.size());
        for (byte[] record : records) {
            codes.add(new String(record, StandardCharsets.UTF_8));
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
            if (!PageLayout.isInitialized(page)) {
                throw new CorruptedIndexException(
                        "page in file is not initialized: pageId=" + pageId, pageId);
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

    /**
     * 인덱스를 통째로 다시 만든다. 파일 교체는 아래 계층이 하고, 여기서는 자기 상태를 되돌린다.
     *
     * 임시 SpatialRecordManager 를 따로 세워 로더에 넘긴다. 적재가 임시 파일 쪽으로만
     * 가야 하고, 그 사이 기존 인덱스는 계속 조회에 응답해야 하기 때문이다.
     *
     * 교체가 끝나면 free list 와 락 맵을 되돌린다. 둘 다 옛 파일 기준으로 쌓인 상태라
     * 그대로 두면 새 파일과 어긋난다. 이 엔진에서 자원을 회수하는 곳은 여기뿐이다.
     */
    public void rebuild(Consumer<SpatialRecordManager> loader) {
        cacheManager.rebuild(tempCm -> {
            SpatialRecordManager tempSrm = new SpatialRecordManager(tempCm, spatialIndex, engineMetrics);
            loader.accept(tempSrm);
        });
        this.overflowFreeList = buildFreeList();
        this.pageLocks.clear();
    }


    /** 아래 계층으로 위임한다. SpatialCacheEngine 이 메트릭을 한곳에서 모으기 위해 거쳐 간다. */
    public int getDirtyPageCount() {
        return cacheManager.getDirtyPageCount();
    }

    /** free list 는 반납이 없으므로 "꺼낸 총량" 과 같다. */
    public int getUsedOverflowPageCount() {
        return OVERFLOW_PAGES - overflowFreeList.size();
    }

    public int getUsedPageCount() {
        return cacheManager.getUsedPageCount();
    }

}