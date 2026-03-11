package geoindex.test;

import geoindex.api.PageResult;
import geoindex.api.SpatialCacheEngine;
import geoindex.api.SpatialRecordManager;
import geoindex.buffer.CacheManager;
import geoindex.cache.CachePolicy;
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

        geoindex.storage.Page page = new geoindex.storage.Page(1);
        geoindex.storage.PageLayout.initializePage(page);
        geoindex.storage.PageLayout.writeRecord(page, "B0001".getBytes());
        dm.writePage(page);

        dm.rebuild(tempDm -> {});

        geoindex.storage.Page after = dm.readPage(1);
        assertFalse(geoindex.storage.PageLayout.isInitialized(after),
                "rebuild 후 기존 페이지는 없어야 한다");

        dm.close();
        System.out.println("DiskManager rebuild 후 기존 데이터 사라짐 ✅");
    }

    @Test
    void DiskManager_rebuild_후_새데이터_저장됨() {
        DiskManager dm = new DiskManager(TEST_FILE);

        geoindex.storage.Page old = new geoindex.storage.Page(1);
        geoindex.storage.PageLayout.initializePage(old);
        geoindex.storage.PageLayout.writeRecord(old, "OLD".getBytes());
        dm.writePage(old);

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

        geoindex.storage.Page page = cm.getPage(5);
        page.getData()[0] = 42;
        cm.putPage(page);

        cm.rebuild(tempCm -> {});

        geoindex.storage.Page after = cm.getPage(5);
        assertNotSame(page, after, "rebuild 후 버퍼가 비워져 새 인스턴스여야 한다");
        assertEquals(0, after.getData()[0], "rebuild 후 기존 데이터가 없어야 한다");

        cm.close();
        System.out.println("CacheManager rebuild 후 버퍼 비워짐 ✅");
    }

    // -------------------------------------------------------------------------
    // SpatialCacheEngine.rebuild()  ← 이제 engine이 rebuild 담당
    // -------------------------------------------------------------------------

    @Test
    void SpatialRecordManager_rebuild_후_기존데이터_사라짐() {
        DiskManager dm = new DiskManager(TEST_FILE);
        CacheManager cm = new CacheManager(dm);
        SpatialRecordManager srm = new SpatialRecordManager(cm, new GeoHashIndex());
        SpatialCacheEngine<String> engine = new SpatialCacheEngine<>(srm);

        srm.put(37.4979, 127.0276, "B0001".getBytes());
        srm.put(37.4985, 127.0280, "B0002".getBytes());
        cm.flush();
        cm.clearCache();

        List<String> before = srm.searchRadiusCodes(37.4979, 127.0276, 5.0);
        assertFalse(before.isEmpty(), "rebuild 전 데이터가 있어야 한다");

        // rebuild는 이제 engine이 담당
        engine.rebuild(tempSrm -> {});

        List<String> after = srm.searchRadiusCodes(37.4979, 127.0276, 5.0);
        assertTrue(after.isEmpty(), "rebuild 후 데이터가 없어야 한다");

        assertEquals(0, engine.getCacheSize(), "rebuild 후 JVM 캐시도 비워야 한다");

        cm.close();
        System.out.println("SpatialRecordManager rebuild 후 기존 데이터 사라짐 ✅");
    }

    @Test
    void SpatialRecordManager_rebuild_후_새데이터_저장됨() {
        DiskManager dm = new DiskManager(TEST_FILE);
        CacheManager cm = new CacheManager(dm);
        SpatialRecordManager srm = new SpatialRecordManager(cm, new GeoHashIndex());
        SpatialCacheEngine<String> engine = new SpatialCacheEngine<>(srm);

        srm.put(37.4979, 127.0276, "OLD_001".getBytes());
        cm.flush();
        cm.clearCache();

        engine.rebuild(tempSrm -> {
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
        SpatialCacheEngine<String> engine = new SpatialCacheEngine<>(srm);

        srm.put(37.4979, 127.0276, "B0001".getBytes());
        cm.flush();
        cm.clearCache();

        engine.rebuild(tempSrm -> {
            List<String> duringRebuild = srm.searchRadiusCodes(37.4979, 127.0276, 5.0);
            assertTrue(duringRebuild.contains("B0001"),
                    "rebuild 중 기존 파일로 서비스되어야 한다");
            System.out.println("rebuild 중 기존 데이터 서비스 확인 ✅");

            tempSrm.put(37.4979, 127.0276, "NEW_001".getBytes());
        });

        cm.close();
    }
}