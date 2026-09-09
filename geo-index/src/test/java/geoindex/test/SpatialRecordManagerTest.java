package geoindex.test;

import geoindex.api.SpatialRecordManager;
import geoindex.buffer.CacheManager;
import geoindex.exception.CorruptedIndexException;
import geoindex.index.GeoHashIndex;
import geoindex.metric.EngineMetrics;
import geoindex.storage.DiskManager;
import geoindex.storage.Page;
import geoindex.storage.PageLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SpatialRecordManagerTest {

    static final String TEST_FILE = "test_spatial.db";
    SpatialRecordManager manager;
    CacheManager cacheManager;
    DiskManager diskManager;

    @BeforeEach
    void setup() {
        EngineMetrics metrics = new EngineMetrics();
        diskManager = new DiskManager(TEST_FILE, metrics);
        cacheManager = new CacheManager(diskManager, metrics);
        manager = new SpatialRecordManager(cacheManager, new GeoHashIndex(), metrics);
    }

    @AfterEach
    void cleanup() throws Exception {
        cacheManager.close();
        Files.deleteIfExists(Path.of(TEST_FILE));
    }

    @Test
    void testSearchRadiusEmpty() {
        List<byte[]> results = manager.searchRadius(37.4979, 127.0276, 5.0);
        assertTrue(results.isEmpty());
    }

    @Test
    void testMultipleInserts() {
        for (int i = 0; i < 100; i++) {
            manager.put(37.4979 + i * 0.0001, 127.0276 + i * 0.0001, ("병원" + i).getBytes());
        }
        List<byte[]> results = manager.searchRadius(37.4979, 127.0276, 5.0);
        System.out.println("검색 결과 수: " + results.size());
        assertTrue(results.size() > 0);
    }
    @Test
    void testSearchRadiusCodesByPageId_기본동작() {
        // 강남 근처 병원 3개 삽입
        manager.put(37.4979, 127.0276, "B0001".getBytes());
        manager.put(37.4985, 127.0280, "B0002".getBytes());
        manager.put(37.4990, 127.0290, "B0003".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        Map<Integer, List<String>> result =
                manager.searchRadiusCodesByPageId(37.4979, 127.0276, 5.0);

        assertFalse(result.isEmpty());
        // 전체 codes 추출
        List<String> allCodes = result.values().stream()
                .flatMap(List::stream)
                .toList();

        assertTrue(allCodes.contains("B0001"));
        assertTrue(allCodes.contains("B0002"));
        assertTrue(allCodes.contains("B0003"));
    }

    @Test
    void testSearchRadiusCodesByPageId_pageId별로_묶임() {
        manager.put(37.4979, 127.0276, "B0001".getBytes());
        manager.put(37.4985, 127.0280, "B0002".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        Map<Integer, List<String>> result =
                manager.searchRadiusCodesByPageId(37.4979, 127.0276, 5.0);

        // 각 pageId에 codes가 있어야 함
        result.forEach((pageId, codes) -> {
            assertFalse(codes.isEmpty());
            System.out.println("pageId: " + pageId + " → codes: " + codes);
        });
    }

    @Test
    void testSearchRadiusCodesByPageId_빈페이지_제외() {
        manager.put(37.4979, 127.0276, "B0001".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        Map<Integer, List<String>> result =
                manager.searchRadiusCodesByPageId(37.4979, 127.0276, 5.0);

        // 빈 pageId는 포함되면 안 됨
        result.forEach((pageId, codes) -> assertFalse(codes.isEmpty()));
    }

    /**
     * overflow 링크만 바뀐 페이지도 flush 되어야 한다.
     *
     * 페이지가 꽉 차면 writeRecord 는 -1 을 반환하고 페이지를 건드리지 않는다.
     * 이어서 setOverflowPageId 가 헤더 4바이트만 고치는데, 이 호출이 dirty 를 남기지
     * 않으면 직전 flush 로 표시가 꺼진 페이지는 깨끗한 채로 남아 다음 flush 가 건너뛴다.
     * 링크가 파일에 안 실리므로 캐시를 비우는 순간 체인 뒤쪽이 통째로 사라진다.
     *
     * 이 테스트의 핵심은 3번 직전의 flush 다. 그게 없으면 페이지가 계속 dirty 라
     * setOverflowPageId 의 markDirty 를 지워도 통과한다.
     *
     * 채우는 건수를 상수에서 역산하는 이유: 한 건이라도 넘치면 overflow 가 flush 전에
     * 생겨 버려, 검증하려는 "꽉 찬 채로 flush 된 페이지"라는 조건이 만들어지지 않는다.
     */
    @Test
    void overflow_링크만_바뀐_페이지도_flush된다() {
        double lat = 37.4979, lng = 127.0276;
        byte[] filler = "FILL".getBytes();

        // 1. primary 페이지를 정확히 꽉 채운다 (overflow 는 아직 생기지 않는다)
        int capacity = (Page.PAGE_SIZE - PageLayout.HEADER_SIZE)
                / (4 + filler.length + PageLayout.SLOT_SIZE);
        for (int i = 0; i < capacity; i++) {
            manager.put(lat, lng, filler);
        }
        assertEquals(0, manager.getUsedOverflowPageCount(),
                "이 시점엔 아직 overflow 가 없어야 한다");

        // 2. flush → primary 페이지의 dirty 가 꺼진다
        cacheManager.flush();

        // 3. 한 건 더 — writeRecord 는 -1, setOverflowPageId 만 페이지를 바꾼다
        manager.put(lat, lng, "AFTER_FLUSH".getBytes());
        assertEquals(1, manager.getUsedOverflowPageCount(),
                "여기서 overflow 가 생겨야 한다");

        // 4. flush → 링크가 파일에 실려야 한다
        cacheManager.flush();

        // 5. 캐시를 비워 파일에서만 읽게 한다
        cacheManager.clearCache();

        List<String> codes = manager.getAllCodesByPageId(new GeoHashIndex().toPageId(lat, lng));
        assertTrue(codes.contains("AFTER_FLUSH"),
                "flush 이후에 붙은 overflow 체인이 파일에 남아야 한다");
        assertEquals(capacity + 1, codes.size(), "체인 전체가 읽혀야 한다");
    }

    @Test
    void testSearchRadiusCodesByPageId_범위밖_미포함() {
        // 강남 삽입
        manager.put(37.4979, 127.0276, "B0001".getBytes());
        // 부산 삽입 (반경 밖)
        manager.put(35.1796, 129.0756, "B9999".getBytes());
        cacheManager.flush();
        cacheManager.clearCache();

        Map<Integer, List<String>> result =
                manager.searchRadiusCodesByPageId(37.4979, 127.0276, 5.0);

        List<String> allCodes = result.values().stream()
                .flatMap(List::stream)
                .toList();

        assertTrue(allCodes.contains("B0001"));
        assertFalse(allCodes.contains("B9999"));
    }

    // -------------------------------------------------------------------------
    // 체인 손상 — PageLayout.setOverflowPageId 로 링크를 인위적으로 망가뜨린다
    // -------------------------------------------------------------------------

    /**
     * 체인이 파일에도 캐시에도 없는 페이지를 가리키면 던진다.
     *
     * 예전에는 break 로 넘어가 결과만 조용히 줄었다. 손상 범위는 4KB 인데
     * 사용자에게는 "그 지역에 병원이 없다" 로 보이므로 아무도 알아채지 못한다.
     */
    @Test
    void 체인이_없는_페이지를_가리키면_예외() {
        double lat = 37.4979, lng = 127.0276;
        manager.put(lat, lng, "B0001".getBytes());
        cacheManager.flush();

        int pageId = new GeoHashIndex().toPageId(lat, lng);
        Page page = cacheManager.getOrCreatePage(pageId);
        PageLayout.setOverflowPageId(page, 999_999_999);   // 아무도 쓴 적 없는 pageId
        cacheManager.flush();
        cacheManager.clearCache();                          // findPage 가 null 을 돌려주게

        CorruptedIndexException e = assertThrows(CorruptedIndexException.class,
                () -> manager.getAllCodesByPageId(pageId));
        assertEquals(pageId, e.getPageId(), "손상된 체인의 primary 를 담아야 한다");
    }

    /**
     * 체인이 가리키는 페이지가 초기화돼 있지 않으면 던진다.
     *
     * getOrCreatePage 는 초기화하지 않은 빈 Page 를 캐시에 넣으므로,
     * 그것을 링크 대상으로 삼으면 savePage 가 중단된 상태를 그대로 재현할 수 있다.
     */
    @Test
    void 체인_페이지가_초기화되지_않았으면_예외() {
        double lat = 37.4979, lng = 127.0276;
        manager.put(lat, lng, "B0001".getBytes());

        int pageId = new GeoHashIndex().toPageId(lat, lng);
        int emptyPageId = pageId + 1;
        cacheManager.getOrCreatePage(emptyPageId);          // 초기화되지 않은 채 캐시에만 존재

        Page page = cacheManager.getOrCreatePage(pageId);
        PageLayout.setOverflowPageId(page, emptyPageId);

        CorruptedIndexException e = assertThrows(CorruptedIndexException.class,
                () -> manager.getAllCodesByPageId(pageId));
        assertEquals(pageId, e.getPageId());
    }

    /**
     * 체인에 사이클이 있으면 던진다.
     *
     * 홉 상한이 없으면 while 이 영원히 끝나지 않는다. 결과가 틀리는 다른 손상과 달리
     * 요청 스레드가 묶여, 같은 칸으로 요청이 반복되면 스레드 풀이 고갈된다.
     *
     * 레코드를 1건만 두는 이유: 상한(OVERFLOW_PAGES)까지 도는 동안 페이지의 레코드가
     * 매 바퀴 누적된다. 페이지가 꽉 차 있으면 수백만 건이 쌓인다.
     */
    @Test
    void 체인에_사이클이_있으면_예외() {
        double lat = 37.4979, lng = 127.0276;
        manager.put(lat, lng, "B0001".getBytes());

        int pageId = new GeoHashIndex().toPageId(lat, lng);
        Page page = cacheManager.getOrCreatePage(pageId);
        PageLayout.setOverflowPageId(page, pageId);         // 자기 자신을 가리킨다

        CorruptedIndexException e = assertThrows(CorruptedIndexException.class,
                () -> manager.getAllCodesByPageId(pageId));
        assertTrue(e.getMessage().contains("hops"), "사이클 메시지여야 한다: " + e.getMessage());
    }

    /**
     * primary 가 초기화돼 있지 않으면 던진다.
     *
     * put 이 페이지 획득까지 쓰기 락 안에서 하므로, 정상 경로로는 초기화 전 페이지가
     * 독자에게 보이지 않는다. 그래서 이 조건은 손상만 뜻한다 — 체인 쪽 두 조건과 같다.
     *
     * 이 테스트는 getOrCreatePage 를 직접 불러 그 상태를 만든다. put 을 거치지 않으므로
     * 락 밖에서 만들어지고, 운영에서는 재현되지 않는 상태다.
     */
    @Test
    void primary가_초기화되지_않았으면_예외() {
        int pageId = new GeoHashIndex().toPageId(37.4979, 127.0276);
        cacheManager.getOrCreatePage(pageId);               // 초기화 전 상태

        CorruptedIndexException e = assertThrows(CorruptedIndexException.class,
                () -> manager.getAllCodesByPageId(pageId));
        assertEquals(pageId, e.getPageId());
    }
}
