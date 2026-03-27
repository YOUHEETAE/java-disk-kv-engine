package geoindex.metric;

public class MetricsSnapshot {

    // Index
    public final long   queryCount;
    public final double avgPageIds;
    public final double avgIntervals;

    // Cache
    public final long   pageHit;
    public final long   pageMiss;
    public final double pageHitRate;
    public final int    cacheSize;
    public final long   evictCount;

    // Disk
    public final long   pageReadCount;
    public final long   pageWriteCount;

    // Storage
    public final long   flushCount;
    public final long   flushedPages;
    public final long   rebuildCount;
    public final long   avgRebuildMs;
    public final int    dirtyPages;

    public MetricsSnapshot(
            long queryCount, double avgPageIds, double avgIntervals,
            long pageHit, long pageMiss, double pageHitRate, int cacheSize, long evictCount,
            long pageReadCount, long pageWriteCount,
            long flushCount, long flushedPages, long rebuildCount, long avgRebuildMs, int dirtyPages
    ) {
        this.queryCount     = queryCount;
        this.avgPageIds     = avgPageIds;
        this.avgIntervals   = avgIntervals;
        this.pageHit        = pageHit;
        this.pageMiss       = pageMiss;
        this.pageHitRate    = pageHitRate;
        this.cacheSize      = cacheSize;
        this.evictCount     = evictCount;
        this.pageReadCount  = pageReadCount;
        this.pageWriteCount = pageWriteCount;
        this.flushCount     = flushCount;
        this.flushedPages   = flushedPages;
        this.rebuildCount   = rebuildCount;
        this.avgRebuildMs   = avgRebuildMs;
        this.dirtyPages     = dirtyPages;
    }
}