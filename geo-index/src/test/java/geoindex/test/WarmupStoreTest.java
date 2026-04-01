package geoindex.test;

import geoindex.cache.WarmupStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

public class WarmupStoreTest {
    static final String TEST_FILE = "warmup_test.store";
    WarmupStore warmupStore;

    @BeforeEach
    void setup() {
      warmupStore = new WarmupStore(Path.of(TEST_FILE));
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(Path.of(TEST_FILE));
    }

    @Test
    void recordAccess_동시_호출_카운트_정확() throws InterruptedException {
        int threadCount = 500;
        int callsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                for (int j = 0; j < callsPerThread; j++) {
                    warmupStore.recordAccess(3766);
                }
                latch.countDown();
            });
        }

        latch.await();
        executor.shutdown();

        List<Integer> top = warmupStore.getTopPageIds(1);
        assertEquals(1, top.size());
        assertEquals(3766, top.get(0));
        assertEquals(50000L, warmupStore.getHitCount(3766));
    }

    @Test
    void getTopPageIds_순서_검증(){
        warmupStore.recordAccess(3766);
        warmupStore.recordAccess(3766);
        warmupStore.recordAccess(4000);
        warmupStore.recordAccess(4000);
        warmupStore.recordAccess(4000);
        warmupStore.recordAccess(2500);

        List<Integer> top = warmupStore.getTopPageIds(3);
        assertEquals(4000, top.get(0));
        assertEquals(3766, top.get(1));
        assertEquals(2500, top.get(2));
    }

    @Test
    void persistAndLoadTest() throws IOException {
        warmupStore.recordAccess(3766);
        warmupStore.recordAccess(3766);
        warmupStore.recordAccess(4000);
        warmupStore.persist();

        WarmupStore warmupStore2 = new WarmupStore(Path.of(TEST_FILE));
        List<Integer> top = warmupStore2.getTopPageIds(2);

        assertIterableEquals(List.of(3766, 4000), top);

        List<String> lines = Files.readAllLines(Path.of(TEST_FILE));

        assertEquals(2, lines.size());
        assertTrue(lines.contains("4000 1"));
        assertTrue(lines.contains("3766 2"));

    }

    @Test
    void 파일_없을때_fresh_start() throws InterruptedException {
        WarmupStore warmupStore = new  WarmupStore(Path.of("non_existent.store"));

        List<Integer> top = warmupStore.getTopPageIds(1);
        assertTrue(top.isEmpty());
        assertEquals(0, warmupStore.getHitCount(3766));
    }
}
