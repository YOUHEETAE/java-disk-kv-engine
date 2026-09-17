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
 */
public class RecordManager {

    private static final int MAX_PAGES = 100000;
    private final CacheManager cacheManager;
    private final Map<String, RecordId> index;
    private int nextOverflowPageId = MAX_PAGES;

    public RecordManager(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
        this.index = new HashMap<>();
    }

    public void put(String key, byte[] value) {
        int pageId = Math.abs(key.hashCode() % MAX_PAGES);
        Page page = cacheManager.getOrCreatePage(pageId);

        if (!PageLayout.isInitialized(page)) {
            PageLayout.initializePage(page);
        }

        index.put(key, writeWithOverflow(pageId, page, value));
    }

    private RecordId writeWithOverflow(int pageId, Page page, byte[] value) {
        int slotId = PageLayout.writeRecord(page, value);

        if(slotId != -1) return new RecordId(pageId, slotId);

        int overflowPageId = PageLayout.getOverflowPageId(page);
        if (overflowPageId == PageLayout.NO_OVERFLOW) {
            overflowPageId = allocateNewPage();
            PageLayout.setOverflowPageId(page, overflowPageId);
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

    private int allocateNewPage() {
        return nextOverflowPageId++;
    }
}