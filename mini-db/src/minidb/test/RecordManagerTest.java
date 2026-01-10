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

}
