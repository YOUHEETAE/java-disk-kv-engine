package geoindex.metric;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 전 계층이 공유하는 누적 카운터. DiskManager → CacheManager → SpatialRecordManager → SpatialCacheEngine
 * 생성자 체인에 <b>같은 인스턴스 하나</b>를 넘겨야 한다 — 계층마다 따로 만들면 각자 자기 것만 올리고
 * getMetrics() 는 최상단 것만 읽어 나머지가 전부 0 으로 보인다.
 * <p>
 * 카운터는 프로세스 수명 동안 단조 증가하고 리셋이 없다. 증가율("최근 5분에 몇 번")은 수집 측이
 * 두 시점의 차이로 낸다 — 엔진이 0 으로 되돌리면 재시작과 리셋을 구분할 수 없어진다.
 * <p>
 * cacheSize · dirtyPages 같은 "지금 이 순간의 상태" 는 여기 없다. 그 값을 아는 객체는 이 인스턴스보다
 * 나중에 만들어지므로, 최상단 SpatialCacheEngine 이 snapshot() 을 부를 때 직접 넘긴다.
 */
public class EngineMetrics {

    public EngineMetrics() {}

    // Index
    private final AtomicLong queryCount     = new AtomicLong();
    private final AtomicLong totalPageIds   = new AtomicLong();

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
    private final AtomicLong warmupFailureCount = new AtomicLong();

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
    public void incrementWarmupFailureCount() { warmupFailureCount.incrementAndGet(); }

    public MetricsSnapshot snapshot(int cacheSize, int dirtyPages, int overflowPageUsed, int usedPageCount) {
        return new MetricsSnapshot(
                new MetricsSnapshot.Index(queryCount.get(), totalPageIds.get()),
                new MetricsSnapshot.Cache(pageHit.get(), pageMiss.get(), evictCount.get(), cacheSize),
                new MetricsSnapshot.Disk(pageReadCount.get(), pageWriteCount.get(), usedPageCount),
                new MetricsSnapshot.Storage(flushCount.get(), flushedPages.get(),
                        rebuildCount.get(), totalRebuildMs.get(), warmupFailureCount.get(),
                        dirtyPages, overflowPageUsed)
        );
    }
}
