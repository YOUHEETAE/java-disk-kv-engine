package geoindex.test;

import geoindex.api.PageResult;
import geoindex.cache.CachePolicy;
import geoindex.cache.PageCacheStore;
import geoindex.metric.EngineMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PageCacheStoreTest {

    @Test
    void put_후_HIT() {
        PageCacheStore<String> store = new PageCacheStore<>(CachePolicy.DEFAULT, new EngineMetrics());

        store.put(100, List.of("H001", "H002", "H003"));

        PageResult<String> result = store.getOrMiss(100, List.of());
        assertTrue(result.isHit());
        assertEquals(3, result.getCached().size());
        System.out.println("put 후 HIT ✅");
    }

    @Test
    void 없는_pageId_MISS() {
        PageCacheStore<String> store = new PageCacheStore<>(CachePolicy.DEFAULT, new EngineMetrics());

        PageResult<String> result = store.getOrMiss(999, List.of("H001"));
        assertFalse(result.isHit());
        assertEquals(List.of("H001"), result.getCodes());
        System.out.println("없는 pageId MISS ✅");
    }

    @Test
    void put_덮어쓰기_중복없음() {
        PageCacheStore<String> store = new PageCacheStore<>(CachePolicy.DEFAULT, new EngineMetrics());

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
        PageCacheStore<String> store = new PageCacheStore<>(CachePolicy.DEFAULT, new EngineMetrics());

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
        PageCacheStore<String> store = new PageCacheStore<>(CachePolicy.DEFAULT, new EngineMetrics());

        store.put(100, List.of("H001", "H002"));
        store.put(200, List.of("H003", "H004"));
        store.clearCache();

        assertFalse(store.getOrMiss(100, List.of()).isHit());
        assertFalse(store.getOrMiss(200, List.of()).isHit());
        assertEquals(0, store.getCacheSize());
        System.out.println("clearCache 후 MISS ✅");
    }
}