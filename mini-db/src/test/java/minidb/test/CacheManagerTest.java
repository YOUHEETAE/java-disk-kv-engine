package minidb.test;


import minidb.buffer.CacheManager;
import minidb.storage.DiskManager;
import minidb.storage.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CacheManagerTest {
    static final String TEST_FILE = "test_cache.db";
    CacheManager cacheManager;
    DiskManager diskManager;

    @BeforeEach
    void setUp() {
        diskManager = new DiskManager(TEST_FILE);
        cacheManager = new CacheManager(diskManager);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (cacheManager != null) {
            cacheManager.close();
        }
        Files.deleteIfExists(Path.of(TEST_FILE));
    }

    @Test
    void  testGetPageCreatesNewPage (){
        Page page = cacheManager.getPage(0);
        assertNotNull(page);
        assertEquals(0, page.getPageId());
        assertFalse(page.isDirty());
    }

    @Test
    void testCacheHits(){
        Page page1 =  cacheManager.getPage(5);
        Page page2 =  cacheManager.getPage(5);
        assertSame(page1, page2);
    }

    @Test
    void testPutPage(){
        Page page1 = cacheManager.getPage(7);
        page1.getData()[0] = 42;
        page1.getData()[1] = 43;
        cacheManager.putPage(page1);
        assertTrue(page1.isDirty());

        Page page2 = cacheManager.getPage(7);
        assertSame(page1, page2);
        assertEquals(42, page2.getData()[0]);
        assertEquals(43, page2.getData()[1]);
    }

    @Test
    void testDiskPersistence(){
        Page page1 = cacheManager.getPage(7);
        page1.getData()[0] = 77;
        page1.getData()[1] = 88;
        cacheManager.putPage(page1);

        cacheManager.close();

        // 새로운 DiskManager와 CacheManager 생성 (인스턴스 필드 재할당)
        diskManager = new DiskManager(TEST_FILE);
        cacheManager = new CacheManager(diskManager);

        Page page2 = cacheManager.getPage(7);

        assertEquals(77,page2.getData()[0]);
        assertEquals(88,page2.getData()[1]);
    }

    @Test
    void testMultiplePages(){
        Page page1 = cacheManager.getPage(7);
        Page page2 = cacheManager.getPage(8);
        Page page3 = cacheManager.getPage(9);

        page1.getData()[0] = 77;
        page2.getData()[0] = 88;
        page3.getData()[0] = 99;

        cacheManager.putPage(page1);
        cacheManager.putPage(page2);
        cacheManager.putPage(page3);

        Page cached1 = cacheManager.getPage(7);
        Page cached2 = cacheManager.getPage(8);
        Page cached3 = cacheManager.getPage(9);

        assertEquals(77,page1.getData()[0]);
        assertEquals(88,page2.getData()[0]);
        assertEquals(99,page3.getData()[0]);

        assertNotSame(page1, page2);
        assertNotSame(page1, page3);
        assertNotSame(page2, page3);

        assertSame(page1, cached1);
        assertSame(page2, cached2);
        assertSame(page3, cached3);
    }


}
