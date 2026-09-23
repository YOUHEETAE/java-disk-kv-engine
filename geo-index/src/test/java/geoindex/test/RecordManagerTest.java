package geoindex.test;

import geoindex.benchmark.RecordManager;
import geoindex.buffer.CacheManager;
import geoindex.metric.EngineMetrics;
import geoindex.storage.DiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class RecordManagerTest {
    static final String TEST_FILE = "test_record.db";
    RecordManager recordManager;
    CacheManager cacheManager;
    DiskManager diskManager;

    @BeforeEach
    void setup(){
        EngineMetrics metrics = new EngineMetrics();
        diskManager = new DiskManager(TEST_FILE, metrics);
        cacheManager = new CacheManager(diskManager, metrics);
        recordManager = new RecordManager(cacheManager);
    }
    @AfterEach
    void cleanup() throws Exception {
        cacheManager.flush();
        diskManager.close();
        Thread.sleep(1000);
        Files.deleteIfExists(Path.of(TEST_FILE));
    }
    @Test
    void testPutAndGet(){
        String key = "Alice";
        byte[] value = key.getBytes();

        recordManager.put(key, value);
        byte[] result = recordManager.get(key);

        assertNotNull(result);
        assertEquals(key, new String(result));
    }

    @Test
    void testGetNonExistentKey(){
        byte[] result = recordManager.get("Alice");
        assertNull(result);
    }

    @Test
    void testOverwrite(){
        String key = "user:1001";
        recordManager.put(key, "Alice".getBytes());

        recordManager.put(key, "Bob".getBytes());
        byte[] result = recordManager.get(key);

        assertEquals("Bob", new String(result));
    }
    @Test
    void testMultipleKeyValues(){
        recordManager.put("user:1001", "Alice".getBytes());
        recordManager.put("user:1002", "Bob".getBytes());
        recordManager.put("user:1003", "john".getBytes());

        byte[] result1 = recordManager.get("user:1001");
        byte[] result2 = recordManager.get("user:1002");
        byte[] result3 = recordManager.get("user:1003");

       assertEquals("Alice", new String(result1));
       assertEquals("Bob", new String(result2));
       assertEquals("john", new String(result3));
    }
    @Test
    void testSlottedPageMultipleRecords() {
        // 같은 Page에 여러 레코드
        recordManager.put("user:1001", "Alice".getBytes());
        recordManager.put("user:1002", "Bob".getBytes());
        recordManager.put("user:1003", "Charlie".getBytes());

        assertEquals("Alice", new String(recordManager.get("user:1001")));
        assertEquals("Bob", new String(recordManager.get("user:1002")));
        assertEquals("Charlie", new String(recordManager.get("user:1003")));
    }

    @Test
    void testOverwriteWithSlottedPage() {
        // 덮어쓰기 (최신 값 확인)
        recordManager.put("user:1001", "Alice".getBytes());
        recordManager.put("user:1001", "Bob".getBytes());
        recordManager.put("user:1001", "Charlie".getBytes());

        assertEquals("Charlie", new String(recordManager.get("user:1001")));
    }
    @Test
    void testPageOverflow() {
        for (int i = 0; i < 100; i++) {
            String key = "key" + i;
            String value = "value_very_long_string_" + i + "_".repeat(50);

            recordManager.put(key, value.getBytes());
        }
        for (int i = 0; i < 100; i++) {
            String key = "key" + i;
            byte[] result = recordManager.get(key);

            assertNotNull(result);
            assertTrue(new String(result).startsWith("value_very_long_string_" + i));
        }
    }
    @Test
    void testSimple() {
        recordManager.put("key1", "value1".getBytes());
        byte[] result = recordManager.get("key1");
        assertEquals("value1", new String(result));
    }

    @Test
    void testIndexBasedO1Access() {
        recordManager.put("user:1001", "Alice".getBytes());
        recordManager.put("user:1002", "Bob".getBytes());
        recordManager.put("user:1003", "Charlie".getBytes());

        byte[] result1 = recordManager.get("user:1001");
        byte[] result2 = recordManager.get("user:1002");
        byte[] result3 = recordManager.get("user:1003");

        assertEquals("Alice", new String(result1));
        assertEquals("Bob", new String(result2));
        assertEquals("Charlie", new String(result3));
    }

    @Test
    void testNonExistentKeyReturnsNull() {
        recordManager.put("key1", "value1".getBytes());

        byte[] result = recordManager.get("nonexistent");

        assertNull(result);
    }

    @Test
    void testOverwriteUpdatesIndex() {
        recordManager.put("key1", "value1".getBytes());
        recordManager.put("key1", "value2".getBytes());
        recordManager.put("key1", "value3".getBytes());

        byte[] result = recordManager.get("key1");

        assertEquals("value3", new String(result));
    }

    /**
     * 1000건 규모의 왕복 — 이 파일에서 유일하게 규모가 있는 테스트다. put 1000번 뒤 전부
     * 제 값으로 읽히는지 본다. 덮어쓰기나 overflow 로 인덱스가 어긋나면 여기서 드러난다.
     *
     * 원래 getMs < 100 을 단정하고 있었다. 머신이 느리거나 다른 테스트와 겹치면 코드가
     * 멀쩡한데도 빨개지는 단정이라 뺐다 — 성능은 benchmark 패키지가 warm-up 과 중앙값으로 잰다.
     */
    @Test
    void 천_건을_넣고_전부_제_값으로_읽힌다() {
        int count = 1000;

        for (int i = 0; i < count; i++) {
            recordManager.put("key" + i, ("value" + i).getBytes());
        }

        for (int i = 0; i < count; i++) {
            byte[] result = recordManager.get("key" + i);
            assertNotNull(result, "key" + i + " 가 사라졌다");
            assertEquals("value" + i, new String(result));
        }
    }

    @Test
    void testOverflowChainWithIndex() {
        for (int i = 0; i < 100; i++) {
            String key = "key" + i;
            String value = "long_value_" + i + "_".repeat(50);
            recordManager.put(key, value.getBytes());
        }

        for (int i = 0; i < 100; i++) {
            String key = "key" + i;
            byte[] result = recordManager.get(key);

            assertNotNull(result);
            assertTrue(new String(result).startsWith("long_value_" + i));
        }
    }

    @Test
    void testMixedOperations() {
        recordManager.put("a", "1".getBytes());
        recordManager.put("b", "2".getBytes());

        assertEquals("1", new String(recordManager.get("a")));
        assertEquals("2", new String(recordManager.get("b")));

        recordManager.put("a", "3".getBytes());

        assertEquals("3", new String(recordManager.get("a")));
        assertEquals("2", new String(recordManager.get("b")));

        recordManager.put("c", "4".getBytes());

        assertEquals("3", new String(recordManager.get("a")));
        assertEquals("2", new String(recordManager.get("b")));
        assertEquals("4", new String(recordManager.get("c")));
    }

    @Test
    void testGetAllValues(){
        recordManager.put("H00001", "value1".getBytes());
        recordManager.put("H00002", "value2".getBytes());
        recordManager.put("H00003", "value3".getBytes());

        List<byte[]> result = recordManager.getAllValues();

        assertEquals(3, result.size());
    }

    @Test
    void testGetAllValuesEmpty(){
        List<byte[]> result = recordManager.getAllValues();
        assertEquals(0, result.size());
    }

    /**
     * 기존 overflow 테스트들은 키마다 해시가 달라 각자 다른 홈 페이지로 간다 — overflow 경로를
     * 타지 않는다. 여기서는 같은 홈 페이지에 떨어지는 키만 골라 한 페이지를 넘치게 한다.
     */
    @Test
    void 같은_홈_페이지로_몰린_레코드는_overflow_체인을_타고도_전부_읽힌다() {
        int home = Math.abs("seed".hashCode() % 100000);
        List<String> keys = new java.util.ArrayList<>();
        for (int i = 0; keys.size() < 12; i++) {
            String k = "k" + i;
            if (Math.abs(k.hashCode() % 100000) == home) keys.add(k);
        }
        byte[] big = new byte[1000];                       // 4KB 페이지에 서너 개면 가득 찬다

        for (String k : keys) recordManager.put(k, (k + new String(big)).getBytes());

        assertTrue(cacheManager.getDirtyPageCount() > 1, "홈 페이지 하나에 다 들어갔다면 overflow 를 안 탄 것이다");
        assertEquals(12, recordManager.getAllValues().size(), "overflow 로 넘어간 레코드를 잃으면 안 된다");
        for (String k : keys) assertTrue(new String(recordManager.get(k)).startsWith(k));
    }
}
