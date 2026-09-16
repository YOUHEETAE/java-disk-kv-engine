package geoindex.api;

import java.util.List;

/**
 * PageCacheStore 가 판정을 돌려주는 타입. HIT 이면 캐시 데이터를, MISS 면 넘겨받은 코드
 * 목록을 든다 — 둘 중 하나만 채워지고 나머지는 null 이다. 생성자가 private 이라 hit / miss
 * 두 갈래로만 만들 수 있고, isHit() 이 어느 갈래인지 답한다.
 *
 * 쓰는 곳은 PageCacheStore 와 SpatialCacheEngine 사이뿐이다. 판정만 돌려주던 3-arg search 가
 * 사라지면서 밖으로 나갈 일이 없어졌다 — cache 패키지로 옮길 후보다.
 */
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