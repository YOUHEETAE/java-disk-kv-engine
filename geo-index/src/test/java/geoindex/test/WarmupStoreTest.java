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
    void saveAndLoadTest() throws IOException {
        warmupStore.recordAccess(3766);
        warmupStore.recordAccess(3766);
        warmupStore.recordAccess(4000);
        warmupStore.saveHitCounts();

        WarmupStore warmupStore2 = new WarmupStore(Path.of(TEST_FILE));
        List<Integer> top = warmupStore2.getTopPageIds(2);

        assertIterableEquals(List.of(3766, 4000), top);

        List<String> lines = Files.readAllLines(Path.of(TEST_FILE));

        assertEquals(2, lines.size());
        assertTrue(lines.contains("4000 1"));
        assertTrue(lines.contains("3766 2"));

    }

    @Test
    void 파일이_깨졌을때도_기동은_살아남는다() throws IOException {
        // 이 파일은 캐시 힌트일 뿐이다. 어떤 이유로 깨져 있어도 엔진 기동을 막으면 안 된다.
        // 생성자가 GeoIndexEngine.builder().build() 안에서 불리므로, 여기서 던지면 서비스가 안 뜬다.
        Path broken = Path.of("warmup_broken.store");
        Files.write(broken, List.of(
                "3766 2",
                "﻿4000 1",          // 에디터가 붙인 BOM — 숫자가 아니다
                "not a number at all"
        ));
        try {
            WarmupStore store = assertDoesNotThrow(() -> new WarmupStore(broken),
                    "숫자가 아닌 줄이 있어도 생성자는 던지면 안 된다");

            store.recordAccess(5000);                       // 살아 있는 객체여야 한다
            assertEquals(1, store.getHitCount(5000));
        } finally {
            Files.deleteIfExists(broken);
        }
    }

    @Test
    void 파일_없을때_fresh_start() throws InterruptedException {
        WarmupStore warmupStore = new  WarmupStore(Path.of("non_existent.store"));

        List<Integer> top = warmupStore.getTopPageIds(1);
        assertTrue(top.isEmpty());
        assertEquals(0, warmupStore.getHitCount(3766));
    }
}
