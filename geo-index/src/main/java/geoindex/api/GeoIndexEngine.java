package geoindex.api;


import geoindex.buffer.CacheManager;
import geoindex.cache.CachePolicy;
import geoindex.cache.WarmupStore;
import geoindex.index.GeoHashIndex;
import geoindex.metric.EngineMetrics;
import geoindex.storage.DiskManager;

import java.nio.file.Path;

/**
 * 엔진 조립 진입점. 내부 컴포넌트 7개(EngineMetrics · DiskManager · CacheManager · GeoHashIndex ·
 * SpatialRecordManager · WarmupStore · SpatialCacheEngine)를 순서대로 엮는다. 조립 순서와
 * 공유 관계(EngineMetrics 하나를 전 계층이 나눠 쓴다)는 붙이는 쪽이 알 필요가 없다.
 *
 * dbFile 과 warmupFile 은 필수다. 빠지면 build() 에서 IllegalStateException — 파일을 만들기
 * 전에 검사하므로 실패해도 흔적이 남지 않는다. cachePolicy 는 선택이고, 안 주면
 * CachePolicy.DEFAULT(TTL 끔 · 크기 무제한 · 예열 3000).
 */
public class GeoIndexEngine {
    private GeoIndexEngine (){}

    public static <T> Builder<T> builder(){
        return new Builder<>();
    }

    public static class Builder<T> {
        private String dbFile;
        private String warmupFile;
        private CachePolicy policy = CachePolicy.DEFAULT;

        public Builder<T> dbFile(String dbFile) {
            this.dbFile = dbFile;
            return this;
        }
        public Builder<T> warmupFile(String warmupFile) {
            this.warmupFile = warmupFile;
            return this;
        }
        public Builder<T> cachePolicy(CachePolicy policy) {
            this.policy = policy;
            return this;
        }

        public SpatialCacheEngine<T> build() {
            if(dbFile == null) throw new IllegalStateException("dbFile is required");
            if (warmupFile == null) throw new IllegalStateException("warmupFile is required");
            EngineMetrics engineMetrics = new EngineMetrics();
            DiskManager diskManager = new DiskManager(dbFile, engineMetrics);
            CacheManager cacheManager = new CacheManager(diskManager, engineMetrics);
            SpatialRecordManager spatialRecordManager = new SpatialRecordManager(cacheManager, new GeoHashIndex(),  engineMetrics);
            WarmupStore warmupStore = new WarmupStore(Path.of(warmupFile));
            return new SpatialCacheEngine<>(spatialRecordManager, policy, engineMetrics, warmupStore);
        }
    }

}
