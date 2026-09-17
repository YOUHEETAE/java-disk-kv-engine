package geoindex.test;

import geoindex.api.PageResult;
import geoindex.cache.CachePolicy;
import geoindex.cache.PageCacheStore;
import geoindex.cache.WarmupStore;
import geoindex.metric.EngineMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PageCacheStoreTest {

    static final String WARMUP_FILE = "test_page_cache_store.store";
    WarmupStore warmupStore;

    @BeforeEach
    void setup() { warmupStore = new WarmupStore(Path.of(WARMUP_FILE)); }

    @AfterEach
    void cleanup() throws IOException { Files.deleteIfExists(Path.of(WARMUP_FILE)); }

    @Test
    void put_후_HIT() {
        PageCacheStore<String> store = new PageCacheStore<>(CachePolicy.DEFAULT, new EngineMetrics(), warmupStore);

        store.put(100, List.of("H001", "H002", "H003"));

        PageResult<String> result = store.getOrMiss(100, List.of());
        assertTrue(result.isHit());
        assertEquals(3, result.getCached().size());
        System.out.println("put 후 HIT ✅");
    }

    @Test
    void 없는_pageId_MISS() {
        PageCacheStore<String> store = new PageCacheStore<>(CachePolicy.DEFAULT, new EngineMetrics(), warmupStore);

        PageResult<String> result = store.getOrMiss(999, List.of("H001"));
        assertFalse(result.isHit());
        assertEquals(List.of("H001"), result.getCodes());
        System.out.println("없는 pageId MISS ✅");
    }

    @Test
    void put_덮어쓰기_중복없음() {
        PageCacheStore<String> store = new PageCacheStore<>(CachePolicy.DEFAULT, new EngineMetrics(), warmupStore);

        List<String> data = List.of("H001", "H002", "H003");
        store.put(100, data);
        store.put(100, data);

        PageResult<String> result = store.getOrMiss(100, List.of());
        assertTrue(result.isHit());
        assertEquals(3, result.getCached().size());
        System.out.println("put 덮어쓰기 중복없음 ✅");
    }

    @Test
    void 동시_put_중복없음() throws InterruptedException {
        PageCacheStore<String> store = new PageCacheStore<>(CachePolicy.DEFAULT, new EngineMetrics(), warmupStore);

        List<String> data = List.of("H001", "H002", "H003");

        Thread t1 = new Thread(() -> store.put(100, data));
        Thread t2 = new Thread(() -> store.put(100, data));
        Thread t3 = new Thread(() -> store.put(100, data));

        t1.start(); t2.start(); t3.start();
        t1.join();  t2.join();  t3.join();

        PageResult<String> result = store.getOrMiss(100, List.of());
        assertTrue(result.isHit());
        assertEquals(3, result.getCached().size());
        System.out.println("동시 put 중복없음 ✅");
    }

    @Test
    void clearCache_후_MISS() {
        PageCacheStore<String> store = new PageCacheStore<>(CachePolicy.DEFAULT, new EngineMetrics(), warmupStore);

        store.put(100, List.of("H001", "H002"));
        store.put(200, List.of("H003", "H004"));
        store.clearCache();

        assertFalse(store.getOrMiss(100, List.of()).isHit());
        assertFalse(store.getOrMiss(200, List.of()).isHit());
        assertEquals(0, store.getCacheSize());
        System.out.println("clearCache 후 MISS ✅");
    }

    // -------------------------------------------------------------------------
    // 방어 복사 — put 이 받은 리스트와 getData 가 내주는 리스트는 캐시 내부와 분리돼야 한다
    // -------------------------------------------------------------------------

    @Test
    void 넘긴_리스트를_나중에_고쳐도_캐시는_그대로다() {
        PageCacheStore<String> store = new PageCacheStore<>(CachePolicy.DEFAULT, new EngineMetrics(), warmupStore);

        List<String> data = new ArrayList<>(List.of("H001", "H002"));
        store.put(100, data);
        data.add("H003");                                   // 호출자가 자기 리스트를 계속 쓴다

        assertEquals(2, store.getOrMiss(100, List.of()).getCached().size(),
                "put 이후 호출자의 변경이 캐시에 스며들면 안 된다");
        System.out.println("넘긴 리스트 변경이 캐시에 영향 없음 ✅");
    }

    @Test
    void 돌려받은_리스트는_고칠_수_없다() {
        PageCacheStore<String> store = new PageCacheStore<>(CachePolicy.DEFAULT, new EngineMetrics(), warmupStore);
        store.put(100, new ArrayList<>(List.of("H001", "H002")));   // 가변으로 넣어도

        List<String> cached = store.getOrMiss(100, List.of()).getCached();

        assertThrows(UnsupportedOperationException.class, () -> cached.add("H003"));
        assertThrows(UnsupportedOperationException.class, () -> cached.set(0, "X"));
        assertEquals(2, store.getOrMiss(100, List.of()).getCached().size());
        System.out.println("돌려받은 리스트 불변 ✅");
    }

    @Test
    void null_원소는_저장_시점에_거부한다() {
        PageCacheStore<String> store = new PageCacheStore<>(CachePolicy.DEFAULT, new EngineMetrics(), warmupStore);

        // 조용히 저장했다가 나중에 읽는 쪽에서 터지는 것보다, 넣는 순간 터지는 편이 낫다
        assertThrows(NullPointerException.class,
                () -> store.put(100, Arrays.asList("H001", null)));
        assertFalse(store.getOrMiss(100, List.of()).isHit(), "거부된 put 은 흔적을 남기면 안 된다");
        System.out.println("null 원소 저장 거부 ✅");
    }

    // -------------------------------------------------------------------------
    // 축출 — 크기가 늘어날 때만 발동해야 한다
    // -------------------------------------------------------------------------

    @Test
    void 꽉_찼을_때_있는_키를_다시_넣으면_축출하지_않는다() {
        EngineMetrics metrics = new EngineMetrics();
        CachePolicy twoSlots = CachePolicy.builder().maxSize(2).build();
        PageCacheStore<String> store = new PageCacheStore<>(twoSlots, metrics, warmupStore);

        store.put(1, List.of("A"));
        store.put(2, List.of("B"));
        store.put(1, List.of("A2"));                        // 교체 — 크기가 늘지 않는다

        assertEquals(2, store.getCacheSize());
        assertTrue(store.isCached(1), "교체된 키는 남아 있어야 한다");
        assertTrue(store.isCached(2), "교체 때문에 다른 키가 밀려나면 안 된다");
        assertEquals(0, metrics.snapshot(0, 0, 0, 0).cache().evictCount(), "교체는 축출이 아니다");
        System.out.println("교체는 축출을 발동하지 않음 ✅");
    }

    @Test
    void 꽉_찼을_때_새_키가_오면_가장_오래_안_쓴_것을_축출한다() {
        EngineMetrics metrics = new EngineMetrics();
        CachePolicy twoSlots = CachePolicy.builder().maxSize(2).build();
        PageCacheStore<String> store = new PageCacheStore<>(twoSlots, metrics, warmupStore);

        store.put(1, List.of("A"));
        store.put(2, List.of("B"));
        store.getOrMiss(1, List.of());                      // 1 을 방금 썼다 → 2 가 가장 오래됨
        store.put(3, List.of("C"));

        assertEquals(2, store.getCacheSize());
        assertTrue(store.isCached(1));
        assertFalse(store.isCached(2), "가장 오래 안 쓴 2 가 나가야 한다");
        assertTrue(store.isCached(3));
        assertEquals(1, metrics.snapshot(0, 0, 0, 0).cache().evictCount());
        System.out.println("새 키 → LRU 축출 ✅");
    }
}
