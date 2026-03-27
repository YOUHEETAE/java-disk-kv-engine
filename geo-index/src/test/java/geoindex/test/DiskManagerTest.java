package geoindex.test;

import geoindex.metric.EngineMetrics;
import geoindex.storage.DiskManager;
import geoindex.storage.Page;
import geoindex.storage.PageLayout;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DiskManagerTest {

    static final String TEST_FILE = "test.db";

    @AfterEach
    void cleanup() throws Exception {
        Files.deleteIfExists(Path.of(TEST_FILE));
    }

    @Test
    void test() {
        DiskManager dm = new DiskManager(TEST_FILE, new EngineMetrics());
        try {
            Page page = new Page(0);
            byte[] data = page.getData();

            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (i % 256);
            }

            dm.writePage(page);
            Page read = dm.readPage(0);

            assertNotNull(read);
            assertArrayEquals(page.getData(), read.getData());
        } finally {
            dm.close();
        }
    }
    @Test
    void testWriteAndReadLargePageId() {
        DiskManager dm = new DiskManager(TEST_FILE, new EngineMetrics());
        try {
            int largePageId = 60_712_140;

            Page page = new Page(largePageId);
            PageLayout.initializePage(page);
            PageLayout.writeRecord(page, "강남병원".getBytes());

            dm.writePage(page);

            Page read = dm.readPage(largePageId);
            assertNotNull(read);
            assertEquals("강남병원", new String(PageLayout.readRecord(read, 0)));
        } finally {
            dm.close();
        }
    }

    @Test
    void testFileSizeIsSparse() throws Exception {
        DiskManager dm = new DiskManager(TEST_FILE, new EngineMetrics());
        int[] pageIds = {60_712_140, 60_712_141, 60_712_200};

        for (int pageId : pageIds) {
            Page page = new Page(pageId);
            PageLayout.initializePage(page);
            dm.writePage(page);
        }
        dm.close();

        long fileSize = Files.size(Path.of(TEST_FILE));
        long dataOffset = 4 + (long) 100_000 * 12; // 1_200_004
        long expectedMax = dataOffset + (long) pageIds.length * Page.PAGE_SIZE;

        System.out.println("파일 크기: " + fileSize + " bytes");
        System.out.println("예상 최대: " + expectedMax + " bytes");

        // 매핑 테이블 적용: 3페이지 × 4KB = 12KB
        // 미적용: 60,712,200 × 4KB = 234GB
        assertEquals(expectedMax, fileSize);
    }

    @Test
    void testReadNonExistentPageReturnsEmpty() {
        DiskManager dm = new DiskManager(TEST_FILE, new EngineMetrics());
        try {
            Page page = dm.readPage(999_999_999);
            assertNotNull(page);
            assertFalse(PageLayout.isInitialized(page));
        } finally {
            dm.close();
        }
    }
}
