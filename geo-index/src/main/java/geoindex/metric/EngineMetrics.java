package geoindex.metric;

import java.util.concurrent.atomic.AtomicLong;

public class EngineMetrics {

    public EngineMetrics() {}

    // Index
    private final AtomicLong queryCount     = new AtomicLong();
    private final AtomicLong totalPageIds   = new AtomicLong();
    private final AtomicLong totalIntervals = new AtomicLong();

    // Cache
    private final AtomicLong pageHit    = new AtomicLong();
    private final AtomicLong pageMiss   = new AtomicLong();
    private final AtomicLong evictCount = new AtomicLong();

    // Disk
    private final AtomicLong pageReadCount  = new AtomicLong();
    private final AtomicLong pageWriteCount = new AtomicLong();

    // Storage
    private final AtomicLong flushCount    = new AtomicLong();
    private final AtomicLong flushedPages  = new AtomicLong();
    private final AtomicLong rebuildCount  = new AtomicLong();
    private final AtomicLong totalRebuildMs = new AtomicLong();

    // increment
    public void incrementQueryCount()      { queryCount.incrementAndGet(); }
    public void addPageIds(int count)      { totalPageIds.addAndGet(count); }
    public void incrementPageHit()         { pageHit.incrementAndGet(); }
    public void incrementPageMiss()        { pageMiss.incrementAndGet(); }
    public void incrementEvictCount()      { evictCount.incrementAndGet(); }
    public void incrementPageReadCount()   { pageReadCount.incrementAndGet(); }
    public void incrementPageWriteCount()  { pageWriteCount.incrementAndGet(); }
    public void incrementFlushedPages() { flushedPages.incrementAndGet(); }
    public void incrementFlushCount()      { flushCount.incrementAndGet(); }
    public void incrementRebuildCount()    { rebuildCount.incrementAndGet(); }
    public void addRebuildMs(long ms)      { totalRebuildMs.addAndGet(ms); }
    public void addIntervals(int count)    { totalIntervals.addAndGet(count); }

    public MetricsSnapshot snapshot(int cacheSize, int dirtyPages) {
        long qCount    = queryCount.get();
        long hitCount  = pageHit.get();
        long missCount = pageMiss.get();
        long reads     = pageReadCount.get();
        long rCount    = rebuildCount.get();

        return new MetricsSnapshot(
                // Index
                qCount,
                qCount > 0 ? (double) totalPageIds.get() / qCount : 0.0,
                qCount > 0 ? (double) totalIntervals.get() / qCount : 0.0,
                // Cache
                hitCount,
                missCount,
                (hitCount + missCount) > 0 ? (double) hitCount / (hitCount + missCount) : 0.0,
                cacheSize,
                evictCount.get(),
                // Disk
                reads,
                pageWriteCount.get(),
                // Storage
                flushCount.get(),
                flushedPages.get(),
                rCount,
                rCount > 0 ? totalRebuildMs.get() / rCount : 0,
                dirtyPages
        );
    }
}