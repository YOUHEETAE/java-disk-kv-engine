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
import java.util.concurrent.atomic.AtomicInteger;
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
 *
 * pageId 배치: 상위 비트가 격자 셀(Morton), 하위 SEQ_BITS 가 체인 순번이다.
 *   primary = cell << SEQ_BITS,  n 번째 overflow = primary | n
 * 순번을 하위에 둔 덕에 "다음 칸 = 현재 + 1" 이 성립하고, 정렬하면 체인이 자기 primary 바로
 * 뒤에 놓여 파일 배치가 공간 인접성을 따라간다. 셀이 다르면 상위 비트가 다르므로 번호가
 * 겹칠 수 없다 — 번호를 따로 발급하던 때는 primary 공간과 겹칠 여지가 있었다.
 *
 * 이 인코딩을 아는 것은 이 클래스뿐이다. 위 계층(검색 결과 맵 · 캐시 키 · 예열)은 셀 번호를
 * 쓰고, 체인에 들어가는 메서드가 그것을 pageId 로 바꾼다. 새로 체인에 들어가는 경로를 만들 때
 * 이 변환을 빠뜨리면 예외 없이 빈 결과가 나온다.
 */
public class SpatialRecordManager {

    /** 체인 순번에 쓰는 하위 비트 수. 한 셀이 가질 수 있는 overflow 칸이 SEQ_MASK 개다. */
    private static final int SEQ_BITS = 10;
    private static final long SEQ_MASK = (1L << SEQ_BITS) - 1;

    private final CacheManager cacheManager;
    private final SpatialIndex spatialIndex;
    private final EngineMetrics engineMetrics;
    private final ConcurrentHashMap<Long, ReentrantReadWriteLock> pageLocks;

    private final AtomicInteger overflowPageCount = new AtomicInteger();

    public SpatialRecordManager(CacheManager cacheManager, SpatialIndex spatialIndex,  EngineMetrics engineMetrics) {
        this.cacheManager = cacheManager;
        this.spatialIndex = spatialIndex;
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
    private ReentrantReadWriteLock getLock(long pageId) {
        return pageLocks.computeIfAbsent(pageId, k -> new ReentrantReadWriteLock());
    }

    public void put(double lat, double lng, byte[] value) {
        if (value.length > PageLayout.MAX_RECORD_SIZE) {
            throw new IllegalArgumentException(
                    "record too large: " + value.length + " > " + PageLayout.MAX_RECORD_SIZE);
        }
        long cell = spatialIndex.toPageId(lat, lng);
        writeWithOverflow(cell, value);
    }

    /**
     * 체인 끝까지 내려가며 레코드를 붙인다. 필요하면 overflow 페이지를 새로 단다.
     *
     * 락이 이 메서드에 있는 이유: 원자 단위가 "체인에 레코드 하나를 붙인다"이고,
     * 그것이 여러 페이지와 여러 PageLayout 호출에 걸쳐 있다. 아래쪽(writeRecord 등)에
     * 걸면 호출 하나하나는 안전해지지만 "꽉 찼나 확인 → 다음 칸 계산 → 링크 표시"
     * 순서가 원자적이지 않아, 두 스레드가 같은 칸을 서로 처음인 줄 알고 세거나 덮는다.
     * PageLayout 은 static 유틸이라 잠글 대상도 없고 체인이라는 개념도 모른다.
     *
     * 페이지 획득도 락 안에 있다. 밖에 두면 getOrCreatePage 가 초기화되지 않은 빈
     * 페이지를 캐시에 넣은 뒤 락을 잡기 전까지 창이 생겨, 처음 쓰이는 칸을 동시에
     * 조회한 독자가 그 페이지를 본다. 대가로 페이지 획득이 락 구간에 들어오는데,
     * rebuild 적재 중에는 savePage 가 flush 때 한 번에 돌아 pageMap 이 계속 비어
     * 있으므로 디스크는 읽지 않는다. flush 뒤에 put 이 오면 락 안에서 읽는다.
     */
    private void writeWithOverflow(long cell, byte[] value) {
        long primaryPageId = toPrimaryPageId(cell);
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

                long overflowPageId = current.getPageId() + 1;
                if((overflowPageId & SEQ_MASK) == 0) {
                    throw new IllegalStateException(
                            "overflow chain full: cell=" + (current.getPageId() >>> SEQ_BITS)
                                    + ", maxChain=" + SEQ_MASK
                                    + "; the grid is too coarse for this dataset");
                }

                if(!PageLayout.hasOverflow(current)) {
                    PageLayout.setHasOverflow(current);
                    overflowPageCount.incrementAndGet();
                }

                current = cacheManager.getOrCreatePage(overflowPageId);
            }
        } finally {
            writeLock.unlock();
        }
    }

    /** 운영 진입점. SpatialCacheEngine 이 부르는 것은 이 하나뿐이다. */
    public Map<Long, List<String>> searchRadiusCodesByPageId(double lat, double lng, double radiusKm) {
        engineMetrics.incrementQueryCount();
        List<Long> pageIds = spatialIndex.getPageIds(lat, lng, radiusKm);
        Map<Long, List<String>> result = new LinkedHashMap<>();

        for (long pageId : pageIds) {
            List<String> codes = readAllCodesFromChain(pageId);
            if (!codes.isEmpty()) result.put(pageId, codes);
        }
        engineMetrics.addPageIds(result.size());
        return result;
    }

    /** pageId 를 이미 아는 경우. 워밍업과 테스트가 쓴다. */
    public List<String> getAllCodesByPageId(long pageId) {
        return readAllCodesFromChain(pageId);
    }

    /**
     * 벤치마크 전용. 운영은 병원 코드만 필요하지만 벤치마크는 Hospital.fromBytes 로
     * 레코드를 복원해야 해서 원본 바이트가 필요하다. 그래서 이 메서드만 반환 타입이 다르다.
     */
    public List<byte[]> searchRadius(double lat, double lng, double radiusKm) {
        List<Long> pageIds = spatialIndex.getPageIds(lat, lng, radiusKm);
        List<byte[]> results = new ArrayList<>();

        for (long pageId : pageIds) {
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
    private List<String> readAllCodesFromChain(long pageId) {
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
    private List<byte[]> readAllRecordsFromChain(long cell) {
        long pageId = toPrimaryPageId(cell);
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

            while (PageLayout.hasOverflow(page)) {
                long overflowPageId = page.getPageId() + 1;
                if((overflowPageId & SEQ_MASK) == 0) {
                    throw new CorruptedIndexException(
                            "chain runs past its cell: primary=" + pageId, pageId);
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
                page = overflowPage;
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
     * 교체가 끝나면 락 맵과 overflow 카운터를 되돌린다. 둘 다 옛 파일 기준으로 쌓인 상태라
     * 그대로 두면 새 파일과 어긋난다. 이 엔진에서 자원을 회수하는 곳은 여기뿐이다.
     */
    public void rebuild(Consumer<SpatialRecordManager> loader) {
        cacheManager.rebuild(tempCm -> {
            SpatialRecordManager tempSrm = new SpatialRecordManager(tempCm, spatialIndex, engineMetrics);
            loader.accept(tempSrm);
        });
        this.overflowPageCount.set(0);
        this.pageLocks.clear();
    }

    /** 셀 번호(Morton)를 primary pageId 로. 하위 SEQ_BITS 는 체인 순번 자리라 비워 둔다. */
    private static long toPrimaryPageId(long cell) {
        return cell << SEQ_BITS;
    }

    /** 아래 계층으로 위임한다. SpatialCacheEngine 이 메트릭을 한곳에서 모으기 위해 거쳐 간다. */
    public int getDirtyPageCount() {
        return cacheManager.getDirtyPageCount();
    }

    /**
     * 체인에 칸을 새로 단 횟수. rebuild 로 0 이 된다.
     *
     * 적재는 임시 SpatialRecordManager 에서 도므로 rebuild 직후에는 0 이다. 파일에 실제로
     * 들어 있는 overflow 페이지 수를 보려면 footer 의 pageId 목록에서 순번 비트가 0 이 아닌
     * 것을 세야 한다 — 그러면 DiskManager 가 이 인코딩을 알아야 해서 지금은 하지 않는다.
     */
    public int getUsedOverflowPageCount() {
        return overflowPageCount.get();
    }

    public int getUsedPageCount() {
        return cacheManager.getUsedPageCount();
    }

}