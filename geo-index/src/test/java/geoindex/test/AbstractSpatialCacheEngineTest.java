package geoindex.test;

import geoindex.api.AbstractSpatialCacheEngine;
import geoindex.api.SpatialCacheEngine;
import geoindex.api.SpatialRecordManager;
import geoindex.buffer.CacheManager;
import geoindex.cache.CachePolicy;
import geoindex.cache.WarmupStore;
import geoindex.exception.WarmupFailedException;
import geoindex.index.GeoHashIndex;
import geoindex.metric.EngineMetrics;
import geoindex.storage.DiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 템플릿 메서드 계층의 계약 — 서비스가 구현하는 loadByCodes 가 실패했을 때 부모가 어떻게 보고하나.
 */
class AbstractSpatialCacheEngineTest {

    static final String DB_FILE = "test_abstract_engine.db";
    static final String WARMUP_FILE = "test_abstract_engine.store";

    EngineMetrics metrics;
    CacheManager cacheManager;
    GeoHashIndex index;
    SpatialRecordManager recordManager;
    SpatialCacheEngine<String> engine;

    /** loadByCodes 를 테스트가 갈아끼울 수 있는 최소 구현체 */
    static class TestEngine extends AbstractSpatialCacheEngine<String> {
        Function<List<String>, Map<String, String>> loader = codes -> {
            Map<String, String> m = new HashMap<>();
            for (String c : codes) m.put(c, "v-" + c);
            return m;
        };
        TestEngine(SpatialCacheEngine<String> e) { super(e); }
        @Override protected Map<String, String> loadByCodes(List<String> codes) { return loader.apply(codes); }
    }

    @BeforeEach
    void setup() {
        metrics = new EngineMetrics();
        DiskManager diskManager = new DiskManager(DB_FILE, metrics);
        cacheManager = new CacheManager(diskManager, metrics);
        index = new GeoHashIndex();
        recordManager = new SpatialRecordManager(cacheManager, index, metrics);
        engine = new SpatialCacheEngine<>(recordManager, CachePolicy.DEFAULT, metrics,
                new WarmupStore(Path.of(WARMUP_FILE)));
    }

    @AfterEach
    void cleanup() throws Exception {
        cacheManager.close();
        Files.deleteIfExists(Path.of(DB_FILE));
        Files.deleteIfExists(Path.of(DB_FILE + ".new"));
        Files.deleteIfExists(Path.of(WARMUP_FILE));
    }

    // -------------------------------------------------------------------------
    // rebuild 는 재구축 · 비우기 · 예열 세 사건이다. 예열이 실패해도 앞의 둘은 이미 끝났다.
    // 원래 예외를 그대로 올리면 호출자가 "rebuild 실패" 로 오해한다 — 타입으로 구분해야 한다.
    // -------------------------------------------------------------------------

    @Test
    void 예열이_실패해도_인덱스는_교체되고_WarmupFailedException으로_구분된다() {
        TestEngine service = new TestEngine(engine);

        recordManager.put(37.4979, 127.0276, "OLD".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();
        int pageId = index.toPageId(37.4979, 127.0276);
        service.search(37.4979, 127.0276, 1.0);            // 접근 기록 → 예열 대상이 생긴다

        RuntimeException dbDown = new RuntimeException("db down");
        service.loader = codes -> { throw dbDown; };        // 예열 때 DB 가 죽는다

        WarmupFailedException thrown = assertThrows(WarmupFailedException.class, () ->
                service.rebuild(loader -> loader.put(37.4979, 127.0276, "NEW")),
                "예열 실패는 원래 예외가 아니라 WarmupFailedException 이어야 한다");

        assertSame(dbDown, thrown.getCause(), "원인은 그대로 실린다");

        // 재구축은 끝났다 — 인덱스에 NEW 가 있고 OLD 는 없다
        List<String> codes = recordManager.getAllCodesByPageId(pageId);
        assertEquals(List.of("NEW"), codes, "예열이 실패했어도 인덱스는 새것이어야 한다");

        // 비우기도 끝났다 — 캐시는 빈 채로 남는다
        assertFalse(engine.isCached(pageId), "예열이 안 됐으니 캐시는 비어 있다");

        // 관측된다
        assertEquals(1, service.getMetrics().warmupFailureCount, "예열 실패는 메트릭으로 남아야 한다");
    }

    // -------------------------------------------------------------------------
    // 예열은 인기 순서대로 채워야 한다. maxSize 에 걸리면 뒤쪽(인기 없는 것)부터 밀려나야
    // 예열의 목적이 산다. getWarmupTargets 가 순서를 잃으면 어느 것이 밀려날지 해시 순서가 정한다.
    // -------------------------------------------------------------------------

    @Test
    void 예열은_인기_순서대로_채워_maxSize에_걸리면_하위가_밀려난다() throws Exception {
        // 서로 다른 페이지 셋 — 강남 · 시청 · 잠실
        double[][] spots = { {37.4979, 127.0276}, {37.5665, 126.9780}, {37.5133, 127.1001} };
        int[] pageIds = new int[3];
        for (int i = 0; i < 3; i++) {
            recordManager.put(spots[i][0], spots[i][1], ("P" + i).getBytes());
            pageIds[i] = index.toPageId(spots[i][0], spots[i][1]);
        }
        cacheManager.flush();
        cacheManager.clearCache();

        // 캐시 슬롯은 2개. 예열 대상은 3개 → 하나는 밀려나야 한다
        WarmupStore store = new WarmupStore(Path.of(WARMUP_FILE));
        SpatialCacheEngine<String> two = new SpatialCacheEngine<>(recordManager,
                CachePolicy.builder().maxSize(2).build(), metrics, store);
        TestEngine service = new TestEngine(two);
        List<String> requested = new java.util.ArrayList<>();
        service.loader = codes -> {                          // DB 에 무엇을 물었는지 기록
            requested.addAll(codes);
            Map<String, String> m = new HashMap<>();
            for (String c : codes) m.put(c, "v-" + c);
            return m;
        };

        // 인기: P0 > P1 > P2
        for (int i = 0; i < 3; i++) store.recordAccess(pageIds[0]);
        for (int i = 0; i < 2; i++) store.recordAccess(pageIds[1]);
        store.recordAccess(pageIds[2]);

        service.warmup();

        // cap — 슬롯 2개니 2개만 채운다. P2 는 애초에 DB 에서 가져오지도 않는다
        assertFalse(requested.contains("P2"), "캐시에 못 들어갈 것을 DB 에서 가져오면 안 된다");
        // isCached(P0)·isCached(P1) 을 여기서 부르면 안 된다 — access-order 라 get 이 순서를 바꾼다.
        // 없는 키(P2)는 get 이 null 이라 재배치가 없다.
        assertEquals(2, two.getCacheSize(), "캐시가 담을 수 있는 만큼만 예열한다");
        assertFalse(two.isCached(pageIds[2]), "가장 인기 없는 P2 는 예열 대상이 아니다");

        // 역순 삽입 — 예열 직후 LRU 순서가 인기 순서다. 첫 미스에서 P1 이 나가고 P0 는 남는다
        two.putCache(999_999, List.of("X"));
        assertTrue(two.isCached(pageIds[0]), "첫 미스에서 가장 인기 있는 P0 가 나가면 안 된다");
        assertFalse(two.isCached(pageIds[1]), "가장 인기 없는 쪽인 P1 이 나가야 한다");
    }

    @Test
    void 예열이_성공하면_예외도_카운터도_없다() {
        TestEngine service = new TestEngine(engine);

        recordManager.put(37.4979, 127.0276, "OLD".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();
        int pageId = index.toPageId(37.4979, 127.0276);
        service.search(37.4979, 127.0276, 1.0);

        assertDoesNotThrow(() -> service.rebuild(loader -> loader.put(37.4979, 127.0276, "NEW")));

        assertTrue(engine.isCached(pageId), "예열이 캐시를 채웠어야 한다");
        assertEquals(0, service.getMetrics().warmupFailureCount);
    }
}
