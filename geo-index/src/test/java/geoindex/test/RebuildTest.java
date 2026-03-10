package geoindex.test;

import geoindex.api.SpatialRecordManager;
import geoindex.buffer.CacheManager;
import geoindex.cache.SpatialCacheEngine;
import geoindex.index.GeoHashIndex;
import geoindex.storage.DiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RebuildTest {

    static final String TEST_FILE = "test_rebuild.db";

    @AfterEach
    void cleanup() throws Exception {
        Files.deleteIfExists(Path.of(TEST_FILE));
        Files.deleteIfExists(Path.of(TEST_FILE + ".new"));
    }

    // -------------------------------------------------------------------------
    // DiskManager.rebuild()
    // -------------------------------------------------------------------------

    @Test
    void DiskManager_rebuild_후_기존데이터_사라짐() {
        DiskManager dm = new DiskManager(TEST_FILE);

        // 기존 데이터 쓰기
        geoindex.storage.Page page = new geoindex.storage.Page(1);
        geoindex.storage.PageLayout.initializePage(page);
        geoindex.storage.PageLayout.writeRecord(page, "B0001".getBytes());
        dm.writePage(page);

        // rebuild (빈 loader)
        dm.rebuild(tempDm -> {});

        // 기존 pageId → 빈 페이지
        geoindex.storage.Page after = dm.readPage(1);
        assertFalse(geoindex.storage.PageLayout.isInitialized(after),
                "rebuild 후 기존 페이지는 없어야 한다");

        dm.close();
        System.out.println("DiskManager rebuild 후 기존 데이터 사라짐 ✅");
    }

    @Test
    void DiskManager_rebuild_후_새데이터_저장됨() {
        DiskManager dm = new DiskManager(TEST_FILE);

        // 기존 데이터
        geoindex.storage.Page old = new geoindex.storage.Page(1);
        geoindex.storage.PageLayout.initializePage(old);
        geoindex.storage.PageLayout.writeRecord(old, "OLD".getBytes());
        dm.writePage(old);

        // rebuild → 새 데이터
        dm.rebuild(tempDm -> {
            geoindex.storage.Page newPage = new geoindex.storage.Page(1);
            geoindex.storage.PageLayout.initializePage(newPage);
            geoindex.storage.PageLayout.writeRecord(newPage, "NEW".getBytes());
            tempDm.writePage(newPage);
        });

        geoindex.storage.Page read = dm.readPage(1);
        assertEquals("NEW", new String(geoindex.storage.PageLayout.readRecord(read, 0)));

        dm.close();
        System.out.println("DiskManager rebuild 후 새 데이터 저장됨 ✅");
    }

    // -------------------------------------------------------------------------
    // CacheManager.rebuild()
    // -------------------------------------------------------------------------

    @Test
    void CacheManager_rebuild_후_버퍼_비워짐() {
        DiskManager dm = new DiskManager(TEST_FILE);
        CacheManager cm = new CacheManager(dm);

        // 버퍼에 페이지 올림
        geoindex.storage.Page page = cm.getPage(5);
        page.getData()[0] = 42;
        cm.putPage(page);

        // rebuild (빈 loader)
        cm.rebuild(tempCm -> {});

        // 버퍼 비워졌으므로 새 인스턴스
        geoindex.storage.Page after = cm.getPage(5);
        assertNotSame(page, after, "rebuild 후 버퍼가 비워져 새 인스턴스여야 한다");
        assertEquals(0, after.getData()[0], "rebuild 후 기존 데이터가 없어야 한다");

        cm.close();
        System.out.println("CacheManager rebuild 후 버퍼 비워짐 ✅");
    }

    // -------------------------------------------------------------------------
    // SpatialRecordManager.rebuild()
    // -------------------------------------------------------------------------

    @Test
    void SpatialRecordManager_rebuild_후_기존데이터_사라짐() {
        DiskManager dm = new DiskManager(TEST_FILE);
        CacheManager cm = new CacheManager(dm);
        SpatialCacheEngine<String> engine = new SpatialCacheEngine<>();
        SpatialRecordManager srm = new SpatialRecordManager(cm, new GeoHashIndex(), engine);

        // 기존 데이터
        srm.put(37.4979, 127.0276, "B0001".getBytes());
        srm.put(37.4985, 127.0280, "B0002".getBytes());
        cm.flush();
        cm.clearCache();

        List<String> before = srm.searchRadiusCodes(37.4979, 127.0276, 5.0);
        assertFalse(before.isEmpty(), "rebuild 전 데이터가 있어야 한다");

        // rebuild (빈 loader)
        srm.rebuild(tempSrm -> {});

        // 파일 비워짐 → 빈 결과
        List<String> after = srm.searchRadiusCodes(37.4979, 127.0276, 5.0);
        assertTrue(after.isEmpty(), "rebuild 후 데이터가 없어야 한다");

        // JVM 캐시도 비워짐
        assertEquals(0, engine.getCacheSize(), "rebuild 후 JVM 캐시도 비워야 한다");

        cm.close();
        System.out.println("SpatialRecordManager rebuild 후 기존 데이터 사라짐 ✅");
    }

    @Test
    void SpatialRecordManager_rebuild_후_새데이터_저장됨() {
        DiskManager dm = new DiskManager(TEST_FILE);
        CacheManager cm = new CacheManager(dm);
        SpatialCacheEngine<String> engine = new SpatialCacheEngine<>();
        SpatialRecordManager srm = new SpatialRecordManager(cm, new GeoHashIndex(), engine);

        // 기존 데이터
        srm.put(37.4979, 127.0276, "OLD_001".getBytes());
        cm.flush();
        cm.clearCache();

        // rebuild → 새 데이터
        srm.rebuild(tempSrm -> {
            tempSrm.put(37.4979, 127.0276, "NEW_001".getBytes());
            tempSrm.put(37.4979, 127.0276, "NEW_002".getBytes());
        });

        List<String> result = srm.searchRadiusCodes(37.4979, 127.0276, 5.0);
        assertTrue(result.contains("NEW_001"), "새 데이터가 있어야 한다");
        assertTrue(result.contains("NEW_002"), "새 데이터가 있어야 한다");
        assertFalse(result.contains("OLD_001"), "기존 데이터는 없어야 한다");

        cm.close();
        System.out.println("SpatialRecordManager rebuild 후 새 데이터 저장됨 ✅");
    }

    @Test
    void rebuild_중_기존_파일로_서비스됨() {
        DiskManager dm = new DiskManager(TEST_FILE);
        CacheManager cm = new CacheManager(dm);
        SpatialRecordManager srm = new SpatialRecordManager(cm, new GeoHashIndex());

        // 기존 데이터
        srm.put(37.4979, 127.0276, "B0001".getBytes());
        cm.flush();
        cm.clearCache();

        // rebuild 중 기존 파일 검색 가능한지 확인
        // (loader 실행 전 기존 데이터 확인)
        srm.rebuild(tempSrm -> {
            // loader 실행 중 기존 srm으로 검색 → 기존 파일 살아있음
            List<String> duringRebuild = srm.searchRadiusCodes(37.4979, 127.0276, 5.0);
            assertTrue(duringRebuild.contains("B0001"),
                    "rebuild 중 기존 파일로 서비스되어야 한다");
            System.out.println("rebuild 중 기존 데이터 서비스 확인 ✅");

            tempSrm.put(37.4979, 127.0276, "NEW_001".getBytes());
        });

        cm.close();
    }
}