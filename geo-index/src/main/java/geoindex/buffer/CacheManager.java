package geoindex.buffer;

import geoindex.metric.EngineMetrics;
import geoindex.storage.DiskManager;
import geoindex.storage.Page;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class CacheManager {
    private final ConcurrentHashMap<Integer, Page> cache;
    private final DiskManager diskManager;
    private final EngineMetrics engineMetrics;
    public CacheManager(DiskManager diskManager, EngineMetrics engineMetrics) {
        this.diskManager = diskManager;
        this.cache = new ConcurrentHashMap<>();
        this.engineMetrics = engineMetrics;
    }



    /**
     * 캐시 → 파일 순으로 찾는다. 어느 쪽에도 없으면 null — 읽기 경로 전용.
     *
     * 검색이 훑는 pageId 는 저장된 데이터가 아니라 반경을 덮는 격자에서 나온다.
     * 그래서 조회 한 번에 "아무도 쓴 적 없는 칸"이 대량으로 섞인다(실측 79%).
     * 그 칸마다 빈 Page 를 만들면 4KB 씩 캐시에 눌러앉고, 회수는 rebuild 밖에 없다.
     *
     * computeIfAbsent 는 매핑 함수가 null 을 반환하면 저장하지 않고 null 을 돌려준다.
     * "없으면 캐시에 넣지 않는다"가 별도 분기 없이 성립하는 이유다.
     */
    public Page findPage(int pageId) {
        return cache.computeIfAbsent(pageId, diskManager::loadPage);
    }

    /**
     * 캐시 → 파일 순으로 찾고, 그래도 없으면 새로 만든다 — 쓰기 경로 전용.
     * 현재 운영 호출자는 rebuild 안의 put 하나뿐이다. 읽기는 findPage 를 쓴다.
     *
     * 앞의 두 단계를 건너뛰고 항상 새로 만들면 안 된다. 이미 레코드가 든 페이지를 덮으면
     * initializePage 가 recordCount 를 0 으로 되돌리고 flush 가 그대로 파일에 써서,
     * 기존 레코드가 예외 없이 사라진다.
     */
    public Page getOrCreatePage(int pageId){
        return cache.computeIfAbsent(pageId, id -> {
            Page page = diskManager.loadPage(id);
            return page != null ? page : new Page(id);
        });
    }

    /**
     * pageId 순으로 쓰는 이유: pageId 가 Morton 코드라 오름차순이 곧 Z-곡선 순서다.
     * savePage 는 처음 보는 pageId 에만 offset 을 이어 붙이므로, 전부 새 페이지인
     * rebuild 에서 파일 배치가 공간 인접성을 따라간다. 해시 순서로 쓰면 인덱스가
     * 만들어낸 인접성이 파일에서 사라져, 반경 쿼리가 파일 전체에 흩어진 seek 이 된다.
     *
     * 다만 overflow 페이지는 별도 번호 공간(32,768~)이라 자기 primary 와 멀리 떨어진다.
     * 체인이 있는 칸의 지역성은 이 정렬로 해결되지 않는다.
     */
    public void flush() {
        engineMetrics.incrementFlushCount();
        List<Page> pages = new ArrayList<>(cache.values());
        pages.sort(Comparator.comparingInt(Page::getPageId));
        for (Page page : pages) {
            // 이 synchronized 는 짝이 없다. 쓰기 경로가 잡는 것은
            // SpatialRecordManager 의 pageLocks(체인 단위 RWLock)라 별개 객체이고,
            // 상호배제는 같은 락 객체를 잡을 때만 성립한다. 지금 안전한 이유는
            // 락이 아니라 "런타임 쓰기가 없다"는 것뿐이다.
            //
            // 쓰기 API 를 열면 flush 도 같은 락에 참여해야 하는데, flush 는 페이지의
            // primary 를 모르므로 락 키를 pageId 로 내려야 한다. 그러면 체인이 한 문
            // 뒤에 있지 못하니 append 를 "overflow 페이지를 완성한 뒤 링크를 건다"로
            // 뒤집어야 독자가 빈 페이지를 보지 않는다.
            synchronized (page) {
                if (page.isDirty()) {
                    diskManager.savePage(page);
                    page.clearDirty();
                    engineMetrics.incrementFlushedPages();
                }
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
    public void rebuild(CacheManagerLoader loader) {
        // 임시 CacheManager에 데이터 구축 (기존 파일 살아있음)
        diskManager.rebuild(tempDm -> {
            CacheManager tempCm = new CacheManager(tempDm, engineMetrics);
            loader.load(tempCm);
            tempCm.flush();   // 임시 파일에 기록
        });

        // rename 완료 후 버퍼 초기화 → 새 파일 기반으로 전환
        cache.clear();
    }

    @FunctionalInterface
    public interface CacheManagerLoader {
        void load(CacheManager cm);
    }

    public int getDirtyPageCount() {
        return (int) cache.values().stream().filter(Page::isDirty).count();
    }

    public int getUsedPageCount() {
        return diskManager.getUsedPageCount();
    }
}
