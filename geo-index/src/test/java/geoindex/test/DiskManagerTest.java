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

    /**
     * 파일 포맷 상수. DiskManager 쪽은 private 이라 값을 여기 맞춰 둔다 —
     * 한쪽만 바뀌면 이 테스트들이 깨진다. 그게 이 복제의 목적이기도 하다.
     */
    static final long HEADER_SIZE  = 16;   // 매직(4) + 버전(4) + 예약(8)
    static final long ENTRY_SIZE   = 16;   // pageId(8) + offset(8)
    static final long TRAILER_SIZE = 12;   // 페이지 수(8) + 매직(4)

    /** 완성된 파일의 정확한 크기. */
    static long expectedFileSize(int pageCount) {
        return HEADER_SIZE + (long) pageCount * Page.PAGE_SIZE
                + (long) pageCount * ENTRY_SIZE + TRAILER_SIZE;
    }

    /**
     * 앞뒤로 지운다. 이 테스트들은 손상된 파일을 일부러 만들므로, 앞에서 지우지 않으면
     * 지난 실행이 남긴 파일 위에서 시작해 엉뚱한 곳에서 터진다.
     */
    @BeforeEach
    @AfterEach
    void cleanup() throws Exception {
        Files.deleteIfExists(Path.of(TEST_FILE));

        Path leftover = Path.of(TEST_FILE + ".new");
        if (Files.isDirectory(leftover)) {
            try (var entries = Files.list(leftover)) {
                for (Path child : entries.toList()) Files.deleteIfExists(child);
            }
        }
        Files.deleteIfExists(leftover);
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

            dm.savePage(page);
            Page read = dm.loadPage(0);

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
            long largePageId = 60_712_140;

            Page page = new Page(largePageId);
            PageLayout.initializePage(page);
            PageLayout.writeRecord(page, "강남병원".getBytes());

            dm.savePage(page);

            Page read = dm.loadPage(largePageId);
            assertNotNull(read);
            assertEquals("강남병원", new String(PageLayout.readRecord(read, 0)));
        } finally {
            dm.close();
        }
    }

    @Test
    void testFileSizeIsSparse() throws Exception {
        DiskManager dm = new DiskManager(TEST_FILE, new EngineMetrics());
        long[] pageIds = {60_712_140, 60_712_141, 60_712_200};

        for (long pageId : pageIds) {
            Page page = new Page(pageId);
            PageLayout.initializePage(page);
            dm.savePage(page);
        }
        dm.sealIndex();   // savePage 만 부른 호출자는 색인을 스스로 완성시켜야 한다
        dm.close();

        long fileSize = Files.size(Path.of(TEST_FILE));

        System.out.println("파일 크기: " + fileSize + " bytes");

        // 조회 방식: 3페이지 × 4KB + 색인 3 × 16B + 트레일러 12B
        // 계산 방식(offset = pageId × 4096)이었다면: 60,712,200 × 4KB ≈ 232GiB
        assertEquals(expectedFileSize(pageIds.length), fileSize);
    }

    @Test
    void testReadNonExistentPageReturnsNull() {
        DiskManager dm = new DiskManager(TEST_FILE, new EngineMetrics());
        try {
            Page page = dm.loadPage(999_999_999);
            assertNull(page);
        } finally {
            dm.close();
        }
    }

    // -------------------------------------------------------------------------
    // footer — 재시작 경로
    // -------------------------------------------------------------------------

    /**
     * 닫고 다시 열어 페이지를 더해도 기존 페이지가 멀쩡한지.
     *
     * nextDataOffset 을 겨냥한다. 재시작 때 이 값을 페이지 수가 아니라 파일 길이나
     * 엔트리 크기로 계산하면 첫 새 페이지가 이미 있는 페이지 위에 떨어지고,
     * 예외 없이 옛 레코드만 사라진다.
     */
    @Test
    void 닫고_다시_열어_페이지를_더해도_기존_페이지가_남는다() {
        DiskManager first = new DiskManager(TEST_FILE, new EngineMetrics());
        Page a = new Page(1_000);
        PageLayout.initializePage(a);
        PageLayout.writeRecord(a, "FIRST".getBytes());
        first.savePage(a);
        first.sealIndex();
        first.close();

        DiskManager second = new DiskManager(TEST_FILE, new EngineMetrics());
        try {
            Page reread = second.loadPage(1_000);
            assertNotNull(reread, "재시작 뒤에도 pageMap 이 복원되어야 한다");
            assertEquals("FIRST", new String(PageLayout.readRecord(reread, 0)));

            Page b = new Page(2_000);
            PageLayout.initializePage(b);
            PageLayout.writeRecord(b, "SECOND".getBytes());
            second.savePage(b);
            second.sealIndex();

            assertEquals("FIRST", new String(PageLayout.readRecord(second.loadPage(1_000), 0)),
                    "새 페이지가 기존 페이지를 덮지 않아야 한다");
            assertEquals("SECOND", new String(PageLayout.readRecord(second.loadPage(2_000), 0)));
        } finally {
            second.close();
        }

        DiskManager third = new DiskManager(TEST_FILE, new EngineMetrics());
        try {
            assertEquals("FIRST", new String(PageLayout.readRecord(third.loadPage(1_000), 0)));
            assertEquals("SECOND", new String(PageLayout.readRecord(third.loadPage(2_000), 0)));
        } finally {
            third.close();
        }
    }

    /** 페이지를 한 장도 안 쓴 파일도 완성 상태여야 한다 — 아니면 다음 기동이 손상으로 거부한다. */
    @Test
    void 페이지가_없는_파일도_완성_상태다() throws Exception {
        new DiskManager(TEST_FILE, new EngineMetrics()).close();

        assertEquals(expectedFileSize(0), Files.size(Path.of(TEST_FILE)));
        assertDoesNotThrow(() -> new DiskManager(TEST_FILE, new EngineMetrics()).close(),
                "빈 파일은 조용히 열려야 한다");
    }

    // -------------------------------------------------------------------------
    // footer — 손상 감지
    // -------------------------------------------------------------------------

    /**
     * 끝 매직이 없으면 거부한다.
     *
     * 이 표식이 없으면 쓰다가 죽은 파일을 완성된 파일로 읽는다. 빈 pageMap 으로 조용히
     * 넘어가면 데이터가 사라진 것처럼 보이고, 그 상태의 put 이 진짜로 덮어쓴다.
     */
    @Test
    void 끝_매직이_망가지면_기동하지_않는다() throws Exception {
        sealedFileWithOnePage();

        byte[] bytes = Files.readAllBytes(Path.of(TEST_FILE));
        bytes[bytes.length - 1] ^= 0xFF;
        Files.write(Path.of(TEST_FILE), bytes);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new DiskManager(TEST_FILE, new EngineMetrics()));
        assertTrue(e.getMessage().contains("end marker"), e.getMessage());
    }

    /** 페이지를 쓰다 죽은 파일 — footer 가 아예 없다. */
    @Test
    void footer_없이_끝난_파일은_기동하지_않는다() throws Exception {
        DiskManager dm = new DiskManager(TEST_FILE, new EngineMetrics());
        Page page = new Page(1_000);
        PageLayout.initializePage(page);
        dm.savePage(page);
        dm.close();                       // sealIndex 를 부르지 않는다 = 쓰다가 죽은 파일

        assertEquals(HEADER_SIZE + Page.PAGE_SIZE, Files.size(Path.of(TEST_FILE)));
        assertThrows(IllegalStateException.class,
                () -> new DiskManager(TEST_FILE, new EngineMetrics()));
    }

    /** 헤더만 쓰고 죽은 파일 — 길이 하한에 걸린다. 빈 파일(0바이트)과 구별되어야 한다. */
    @Test
    void 헤더만_있는_파일은_기동하지_않는다() throws Exception {
        Files.write(Path.of(TEST_FILE), new byte[]{0x4D, 0x44, 0x42, 0x31, 0, 0, 0, 1});

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new DiskManager(TEST_FILE, new EngineMetrics()));
        assertTrue(e.getMessage().contains("incomplete"), e.getMessage());
    }

    /** 우리 파일이 아니면 경로를 잘못 짚은 것이다 — 재구축이 아니라 설정을 봐야 한다. */
    @Test
    void 앞_매직이_다르면_우리_파일이_아니라고_말한다() throws Exception {
        byte[] alien = new byte[64];
        alien[0] = 'P';
        alien[1] = 'A';
        alien[2] = 'R';
        alien[3] = '1';
        Files.write(Path.of(TEST_FILE), alien);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new DiskManager(TEST_FILE, new EngineMetrics()));
        assertTrue(e.getMessage().contains("not an index file"), e.getMessage());
    }

    /** 포맷 버전이 다르면 재구축하라고 말한다 — 격자 크기·페이지 크기는 파일에 적히지 않는다. */
    @Test
    void 포맷_버전이_다르면_거부한다() throws Exception {
        sealedFileWithOnePage();

        byte[] bytes = Files.readAllBytes(Path.of(TEST_FILE));
        bytes[7] = 99;                    // [4~7] = 버전, big-endian
        Files.write(Path.of(TEST_FILE), bytes);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new DiskManager(TEST_FILE, new EngineMetrics()));
        assertTrue(e.getMessage().contains("format version"), e.getMessage());
    }

    /**
     * 임시 파일 자리를 비우지 못하면 재구축을 거부한다.
     *
     * 잔재를 지우지 못한 채 진행하면 그 파일이 검증을 통과할 수 있다 — seal 까지 끝내고 rename
     * 전에 죽은 잔재가 그렇다. 그러면 지난 실행의 페이지가 pageMap 에 실려 새 인덱스에 섞이고,
     * 검색이 삭제된 레코드를 돌려주는데 예외는 나지 않는다.
     *
     * 삭제를 막는 수단으로 디렉터리를 쓴다. 실제 원인은 "다른 프로세스가 열고 있다"이지만 그건
     * Windows 에서만 재현되고, 비어 있지 않은 디렉터리는 어느 OS 에서든 예외가 난다. 검증하려는
     * 것은 원인이 아니라 "못 지우면 중단하고 라이브 인덱스를 건드리지 않는다"다.
     */
    @Test
    void 임시_파일_자리를_비우지_못하면_재구축을_거부한다() throws Exception {
        DiskManager dm = new DiskManager(TEST_FILE, new EngineMetrics());
        Page live = new Page(1_000);
        PageLayout.initializePage(live);
        PageLayout.writeRecord(live, "LIVE".getBytes());
        dm.savePage(live);
        dm.sealIndex();

        Path leftover = Path.of(TEST_FILE + ".new");
        Files.createDirectory(leftover);
        Files.createFile(leftover.resolve("blocker"));

        try {
            RuntimeException e = assertThrows(RuntimeException.class,
                    () -> dm.rebuild(tempDm -> fail("잔재를 못 지웠으면 로더가 돌아선 안 된다")));
            assertTrue(e.getMessage().contains("leftover temp file"), e.getMessage());

            assertEquals("LIVE", new String(PageLayout.readRecord(dm.loadPage(1_000), 0)),
                    "재구축을 거부했으면 라이브 인덱스는 그대로여야 한다");
        } finally {
            dm.close();
        }
    }

    private void sealedFileWithOnePage() {
        DiskManager dm = new DiskManager(TEST_FILE, new EngineMetrics());
        Page page = new Page(1_000);
        PageLayout.initializePage(page);
        PageLayout.writeRecord(page, "SEED".getBytes());
        dm.savePage(page);
        dm.sealIndex();
        dm.close();
    }
}
