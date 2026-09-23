package geoindex.test;

import geoindex.api.GeoIndexEngine;
import geoindex.api.SpatialCacheEngine;
import geoindex.cache.CachePolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 빌더는 공개 진입점인데 테스트가 하나도 없었다. 필수값 검증과 — 더 중요하게 — 컴포넌트 7개가
 * 올바른 순서로 엮여 실제로 검색이 되는지를 본다. 조립이 틀리면 컴파일은 되고 런타임에 터진다.
 */
class GeoIndexEngineTest {

    static final String DB_FILE = "test_builder.db";
    static final String WARMUP_FILE = "test_builder.store";
    SpatialCacheEngine<String> built;

    @AfterEach
    void cleanup() throws Exception {
        if (built != null) built.close();
        Files.deleteIfExists(Path.of(DB_FILE));
        Files.deleteIfExists(Path.of(DB_FILE + ".new"));
        Files.deleteIfExists(Path.of(WARMUP_FILE));
    }

    @Test
    void dbFile_없이는_만들_수_없다() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                GeoIndexEngine.<String>builder().warmupFile(WARMUP_FILE).build());
        assertTrue(e.getMessage().contains("dbFile"), "어느 값이 빠졌는지 메시지에 있어야 한다");
    }

    @Test
    void warmupFile_없이는_만들_수_없다() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                GeoIndexEngine.<String>builder().dbFile(DB_FILE).build());
        assertTrue(e.getMessage().contains("warmupFile"));
        assertFalse(Files.exists(Path.of(DB_FILE)), "검증 실패 시 파일을 만들면 안 된다");
    }

    @Test
    void 조립된_엔진으로_색인하고_검색할_수_있다() {
        built = GeoIndexEngine.<String>builder()
                .dbFile(DB_FILE)
                .warmupFile(WARMUP_FILE)
                .cachePolicy(CachePolicy.builder().maxSize(10).warmupSize(5).build())
                .build();

        // 빌더가 엮은 7개가 서로 맞물려야 이 둘이 된다 — rebuild 는 DiskManager→CacheManager→
        // SpatialRecordManager 를, search 는 GeoHashIndex→PageCacheStore→WarmupStore 를 지난다
        built.rebuild(srm -> srm.put(37.4979, 127.0276, "B0001".getBytes()));

        List<String> result = built.search(37.4979, 127.0276, 1.0, codes -> {
            Map<String, String> m = new HashMap<>();
            for (String c : codes) m.put(c, "v-" + c);
            return m;
        });

        assertEquals(List.of("v-B0001"), result);
        assertEquals(1, built.getMetrics().cache().pageMiss(), "EngineMetrics 하나를 전 계층이 공유해야 한다");
    }
}
