package minidb.test;

import minidb.api.RecordManager;
import minidb.buffer.CacheManager;
import minidb.storage.DiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;


class RecordManagerTest {
    static final String TEST_FILE = "test_record.db";
    RecordManager recordManager;
    CacheManager cacheManager;
    DiskManager diskManager;

    @BeforeEach
    void setup(){
        diskManager = new DiskManager(TEST_FILE);
        cacheManager = new CacheManager(diskManager);
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

    @Test
    void testPerformanceO1vsOn() {
        int count = 1000;

        long startPut = System.nanoTime();
        for (int i = 0; i < count; i++) {
            recordManager.put("key" + i, ("value" + i).getBytes());
        }
        long endPut = System.nanoTime();

        long startGet = System.nanoTime();
        for (int i = 0; i < count; i++) {
            byte[] result = recordManager.get("key" + i);
            assertNotNull(result);
            assertEquals("value" + i, new String(result));
        }
        long endGet = System.nanoTime();

        long putMs = (endPut - startPut) / 1_000_000;
        long getMs = (endGet - startGet) / 1_000_000;

        System.out.println("=== Performance Test ===");
        System.out.println("1000 puts: " + putMs + "ms");
        System.out.println("1000 gets: " + getMs + "ms");
        System.out.println("Avg get:   " + (getMs / (double)count) + "ms");

        assertTrue(getMs < 100, "1000 O(1) reads should complete < 100ms");
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

}
