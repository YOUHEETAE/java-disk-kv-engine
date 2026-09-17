package geoindex.metric;

/**
 * getMetrics() 가 돌려주는 한 시점의 값. 계층별로 나눠 담는다 — 어느 층의 값인지가 읽는 쪽 코드에
 * 그대로 보이고, 생성자 인자가 한눈에 세어지는 크기라 자리 바뀜 사고가 나지 않는다.
 * <p>
 * 누적 카운터는 원값 그대로 담고 비율·평균은 메서드로 계산한다. 수집 측이 두 시점의 차이로
 * 구간 평균을 낼 수 있어야 하는데, 평균을 저장해 버리면 그게 안 된다.
 */
public record MetricsSnapshot(Index index, Cache cache, Disk disk, Storage storage) {

    /** 검색 한 번이 몇 개 페이지로 퍼지는가. */
    public record Index(long queryCount, long totalPageIds) {
        public double avgPageIds() {
            return queryCount > 0 ? (double) totalPageIds / queryCount : 0.0;
        }
    }

    /** 페이지 캐시. cacheSize 는 지금 이 순간의 상태, 나머지는 누적. */
    public record Cache(long pageHit, long pageMiss, long evictCount, int cacheSize) {
        public double hitRate() {
            long total = pageHit + pageMiss;
            return total > 0 ? (double) pageHit / total : 0.0;
        }
    }

    /** 파일 I/O. usedPageCount 는 파일에 실제로 기록된 페이지 수 — 지금 이 순간의 상태. */
    public record Disk(long pageReadCount, long pageWriteCount, int usedPageCount) {}

    /** 버퍼 flush 와 재구축. dirtyPages · overflowPageUsed 는 지금 이 순간의 상태, 나머지는 누적. */
    public record Storage(long flushCount, long flushedPages,
                          long rebuildCount, long totalRebuildMs, long warmupFailureCount,
                          int dirtyPages, int overflowPageUsed) {
        /** 성공한 재구축의 평생 평균. 최근 한 번이 궁금하면 totalRebuildMs 의 차이로 본다. */
        public long avgRebuildMs() {
            return rebuildCount > 0 ? totalRebuildMs / rebuildCount : 0;
        }
    }
}
