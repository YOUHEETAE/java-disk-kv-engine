package geoindex.test;

import geoindex.api.SpatialRecordManager;
import geoindex.buffer.CacheManager;
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
}
