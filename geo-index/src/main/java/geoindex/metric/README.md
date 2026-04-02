# Metric 모듈

엔진 전 레이어에 걸친 단일 메트릭 수집 계층

---

## 왜 공유 인스턴스인가

메트릭은 여러 레이어(DiskManager, CacheManager, SpatialRecordManager, PageCacheStore)에 분산된다. 레이어마다 별도 인스턴스를 가지면 각자가 기록한 카운터가 서로 연결되지 않아 수집된 데이터가 불완전해진다.

```
DiskManager(자체 인스턴스)          → pageReadCount 기록
CacheManager(자체 인스턴스)         → flushCount 기록
SpatialCacheEngine(다른 인스턴스)   → getMetrics() → pageReadCount = 0 (항상)
```

단일 EngineMetrics 인스턴스를 생성자 주입으로 모든 레이어에 전달하면, 어느 레이어에서 기록하든 같은 카운터에 누적된다.

```
EngineMetrics metrics = new EngineMetrics();
DiskManager dm         = new DiskManager(filePath, metrics);
CacheManager cm        = new CacheManager(dm, metrics);
SpatialRecordManager srm = new SpatialRecordManager(cm, index, metrics);
SpatialCacheEngine engine = new SpatialCacheEngine(srm, metrics);

engine.getMetrics()  // 모든 레이어 카운터 정확히 집계됨
```

---

## 클래스

### EngineMetrics.java

각 레이어가 공유하는 카운터 저장소. `AtomicLong`으로 thread-safe하게 누적한다.

**카운터 구성:**
```
Index   : queryCount, totalPageIds, totalIntervals
Cache   : pageHit, pageMiss, evictCount
Disk    : pageReadCount, pageWriteCount
Storage : flushCount, flushedPages, rebuildCount, totalRebuildMs
```

**주요 메서드:**
```java
// 각 레이어에서 호출
void incrementQueryCount()
void incrementPageHit()
void incrementPageMiss()
void incrementPageReadCount()
void incrementPageWriteCount()
void incrementFlushCount()
void incrementFlushedPages()
void incrementEvictCount()
void incrementRebuildCount()
void addPageIds(int count)
void addIntervals(int count)
void addRebuildMs(long ms)

// SpatialCacheEngine에서만 호출
MetricsSnapshot snapshot(int cacheSize, int dirtyPages, int overflowPageUsed, int usedPageCount)
```

**snapshot() 설계:**

`cacheSize`와 `dirtyPages`는 "지금 이 순간의 상태"로 누적 카운터가 아니다. EngineMetrics가 Supplier로 들고 있으면 생성 시점에 의존성 문제가 생기므로, 최상단 레이어(SpatialCacheEngine)가 호출 시점에 직접 전달한다.

```java
// SpatialCacheEngine.getMetrics()
return engineMetrics.snapshot(
    (int) pageCacheStore.getCacheSize(),
    spatialRecordManager.getDirtyPageCount(),
    spatialRecordManager.getUsedOverflowPageCount(),
    spatialRecordManager.getUsedPageCount()
);
```

---

### MetricsSnapshot.java

특정 시점의 메트릭 값을 담는 불변 DTO. `snapshot()` 호출 시 평균값 계산이 완료된 상태로 반환된다.

```java
// Index
long   queryCount
double avgPageIds      // totalPageIds / queryCount
double avgIntervals    // totalIntervals / queryCount

// Cache
long   pageHit
long   pageMiss
double pageHitRate     // pageHit / (pageHit + pageMiss)
int    cacheSize
long   evictCount

// Disk
long   pageReadCount
long   pageWriteCount

// Storage
long   flushCount
long   flushedPages
long   rebuildCount
long   avgRebuildMs      // totalRebuildMs / rebuildCount
int    dirtyPages
int    overflowPageUsed  // 현재 사용 중인 overflow 페이지 수 (핫스팟 모니터링용)
int    usedPageCount     // 실제 디스크에 데이터가 기록된 pageId 수
```

---

## Spring 연동

이 엔진은 메트릭 시스템에 의존하지 않는다. Spring 서비스에서 Micrometer Gauge로 연결하는 것을 권장한다.

```java
@Bean
MeterBinder engineMetrics(SpatialCacheEngine<?> engine) {
    return registry -> {
        Gauge.builder("engine.cache.hit_rate",  engine, e -> e.getMetrics().pageHitRate).register(registry);
        Gauge.builder("engine.cache.size",      engine, e -> e.getMetrics().cacheSize).register(registry);
        Gauge.builder("engine.disk.read_count", engine, e -> e.getMetrics().pageReadCount).register(registry);
        // ...
    };
}
```

---

## 제약사항

```
비원자적 조회:
  snapshot()은 각 AtomicLong을 순차적으로 읽는다.
  두 값을 읽는 사이에 연산이 끼어들 수 있어 완전한 일관성은 보장하지 않는다.
  → Prometheus scrape 간격(15s) 수준에서는 허용 범위
  → pageHitRate처럼 단일 메서드에서 계산된 값은 동일 시점 기준으로 계산됨

JVM 프로세스 종료 = 카운터 초기화
  → 재시작 시 0부터 다시 집계
```

---

## 의존성

```
metric/
  EngineMetrics    ← 의존성 없음
  MetricsSnapshot  ← 의존성 없음

DiskManager         → EngineMetrics (pageRead/Write)
CacheManager        → EngineMetrics (flush/flushedPages)
SpatialRecordManager → EngineMetrics (query/pageIds)
PageCacheStore      → EngineMetrics (hit/miss/evict)
SpatialCacheEngine  → EngineMetrics (snapshot 조합)
```
