package minidb.buffer;

import minidb.storage.DiskManager;
import minidb.storage.Page;

import java.util.HashMap;

public class CacheManager {
    private final HashMap<Integer, Page> cache;
    private final DiskManager diskManager;
    public CacheManager(DiskManager diskManager) {
        this.diskManager = diskManager;
        this.cache = new HashMap<>();
    }

    public Page getPage(int pageId) {
        Page page = cache.get(pageId);
        if (page != null) {
            return page;
        }

        page = diskManager.readPage(pageId);
        if (page == null){
            page = new Page(pageId);
        }
        cache.put(pageId, page);
        return page;
    }
    public void putPage(Page page) {
        page.markDirty();
        cache.put(page.getPageId(),page);
    }

    public void flush() {
        for (Page page : cache.values()) {
            if(page.isDirty()){
            diskManager.writePage(page);
            page.clearDirty();
            }
        }
    }

    public void clearCache() {
        cache.clear();
    }

    public void close() {
        flush();
        diskManager.close();
    }
}
