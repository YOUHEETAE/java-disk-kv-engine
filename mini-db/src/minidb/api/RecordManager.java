package minidb.api;

import minidb.buffer.CacheManager;
import minidb.storage.Page;
import minidb.storage.PageLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RecordManager {

    private static final int MAX_PAGES = 10000;
    private final CacheManager cacheManager;
    private final Map<String, RecordId> index;

    public RecordManager(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
        this.index = new HashMap<>();
    }

    public void put(String key, byte[] value) {
        int pageId = Math.abs(key.hashCode() % MAX_PAGES);
        Page page = cacheManager.getPage(pageId);

        if (!PageLayout.isInitialized(page)) {
            PageLayout.initializePage(page);
        }

        int slotId = writeWithOverflow(pageId, page, value);
        cacheManager.putPage(page);
        index.put(key, new RecordId(pageId, slotId));
    }

    private int writeWithOverflow(int pageId, Page page, byte[] value) {
        int slotId = PageLayout.writeRecord(page, value);

        if (slotId == -1) { // 공간 부족 → overflow
            int overflowPageId = PageLayout.getOverflowPageId(page);
            if (overflowPageId == PageLayout.NO_OVERFLOW) {
                overflowPageId = allocateNewPage();
                PageLayout.setOverflowPageId(page, overflowPageId);
            }
            Page overflowPage = cacheManager.getPage(overflowPageId);
            if (!PageLayout.isInitialized(overflowPage)) {
                PageLayout.initializePage(overflowPage);
            }
            return writeWithOverflow(overflowPageId, overflowPage, value);
        }

        return slotId;
    }

    public byte[] get(String key) {
        RecordId rid = index.get(key);
        if (rid == null) return null;

        Page page = cacheManager.getPage(rid.getPageId());
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
        for (int pageId = 0; pageId < MAX_PAGES; pageId++) {
            Page page = cacheManager.getPage(pageId);
            if (!PageLayout.isInitialized(page)) return pageId;
        }
        throw new IllegalStateException("no available pages");
    }
}