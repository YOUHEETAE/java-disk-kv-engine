package minidb.test;

import minidb.storage.DiskManager;
import minidb.storage.Page;
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
        DiskManager dm = new DiskManager(TEST_FILE);
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
}
