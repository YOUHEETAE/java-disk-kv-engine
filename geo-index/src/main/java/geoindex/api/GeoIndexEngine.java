package geoindex.api;


import geoindex.buffer.CacheManager;
import geoindex.cache.CachePolicy;
import geoindex.cache.WarmupStore;
import geoindex.index.GeoHashIndex;
import geoindex.metric.EngineMetrics;
import geoindex.storage.DiskManager;

import java.nio.file.Path;

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
            WarmupStore warmupStore = warmupFile != null ? new WarmupStore(Path.of(warmupFile)) : null;
            return new SpatialCacheEngine<>(spatialRecordManager, policy, engineMetrics, warmupStore);
        }
    }

}
