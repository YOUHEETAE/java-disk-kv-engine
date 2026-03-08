package geoindex.api;

import java.util.List;

public class PageResult<T> {

    private final int pageId;
    private final List<T> cached;
    private final List<String> codes;

    private PageResult(int pageId, List<T> cached, List<String> codes) {
        this.pageId = pageId;
        this.cached = cached;
        this.codes = codes;
    }

    public static <T> PageResult<T> hit(int pageId, List<T> cached) {
        return new PageResult<>(pageId, cached, null);
    }

    public static <T> PageResult<T> miss(int pageId, List<String> codes) {
        return new PageResult<>(pageId, null, codes);
    }

    public boolean isHit() { return cached != null; }
    public int getPageId() { return pageId; }
    public List<T> getCached() { return cached; }
    public List<String> getCodes() { return codes; }
}