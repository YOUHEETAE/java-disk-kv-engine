package geoindex.benchmark;

import geoindex.buffer.CacheManager;
import geoindex.storage.Page;
import geoindex.storage.PageLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 0 의 키-값 저장소. FullScanBenchmark 의 비교 기준선이다.
 *
 * 공간 인덱스로 넘어간 뒤에도 남겨둔 이유: "색인 없는 KV 저장소 vs 공간 색인" 을
 * 재는 것이 벤치마크의 목적이라, 이것을 지우면 비교 대상이 없어진다.
 *
 * 엔진의 공개 API 가 아니다. 락이 하나도 없어 스레드 안전하지 않고,
 * put 에 레코드 크기 검사도 없다 — 벤치마크가 단일 스레드로 고정 크기
 * Hospital 레코드만 넣기 때문에 성립한다.
 *
 * pageId 배치는 SpatialRecordManager 와 같다 — 상위 비트가 홈 페이지, 하위 SEQ_BITS 가
 * 체인 순번이다. 색인 방식만 다르고(해시 vs Morton) 페이지 배치는 같아야, Full Scan 과
 * 공간 색인을 같은 조건에서 비교한 값이 된다.
 */
public class RecordManager {

    private static final int MAX_PAGES = 100000;

    /** 체인 순번에 쓰는 하위 비트 수. 한 홈 페이지가 가질 수 있는 overflow 칸이 SEQ_MASK 개다. */
    private static final int  SEQ_BITS = 10;
    private static final long SEQ_MASK = (1L << SEQ_BITS) - 1;

    private final CacheManager cacheManager;
    private final Map<String, RecordId> index;

    public RecordManager(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
        this.index = new HashMap<>();
    }

    public void put(String key, byte[] value) {
        long pageId = (long) Math.abs(key.hashCode() % MAX_PAGES) << SEQ_BITS;
        Page page = cacheManager.getOrCreatePage(pageId);

        if (!PageLayout.isInitialized(page)) {
            PageLayout.initializePage(page);
        }

        index.put(key, writeWithOverflow(pageId, page, value));
    }

    private RecordId writeWithOverflow(long pageId, Page page, byte[] value) {
        int slotId = PageLayout.writeRecord(page, value);

        if(slotId != -1) return new RecordId(pageId, slotId);

        // 순번이 꽉 차면 자리올림이 하위 비트를 전부 0 으로 만든다 → 옆 홈 페이지를 덮기 직전
        long overflowPageId = pageId + 1;
        if ((overflowPageId & SEQ_MASK) == 0) {
            throw new IllegalStateException(
                    "overflow chain full: home=" + (pageId >>> SEQ_BITS) + ", maxChain=" + SEQ_MASK);
        }

        if (!PageLayout.hasOverflow(page)) {
            PageLayout.setHasOverflow(page);
        }
        Page overflowPage = cacheManager.getOrCreatePage(overflowPageId);
        if (!PageLayout.isInitialized(overflowPage)) {
            PageLayout.initializePage(overflowPage);
        }
        return writeWithOverflow(overflowPageId, overflowPage, value);
    }

    public byte[] get(String key) {
        RecordId rid = index.get(key);
        if (rid == null) return null;

        Page page = cacheManager.getOrCreatePage(rid.getPageId());
        return PageLayout.readRecord(page, rid.getSlotId());
    }

    public List<byte[]> getAllValues() {
        List<byte[]> values = new ArrayList<>();
        for (String key : index.keySet()) {
            byte[] value = get(key);
            if (value != null) values.add(value);
        }
        return values;
    }

}