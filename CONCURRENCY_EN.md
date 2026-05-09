# MiniDB Concurrency Bug Report

## Why concurrency is especially critical for this engine

```
Typical DB cache failure mode:
  Cache MISS → re-query DB → always returns fresh data
  → Wrong cache entries auto-recover after TTL expiry

MiniDB's structural difference:
  put(lat, lng, hospitalCode) → compute pageId → write permanently to Page
  search(lat, lng, radius)   → pageId → read Page directly

  One wrong Page write = wrong search results forever
  No recovery until cache is fully rebuilt (rebuild())
```

**Data correctness = the engine's lifeline**

---

## Bug 1. `ByteBuffer.position()` shared state collision

**Symptom:**
```
java.nio.BufferUnderflowException
  at PageLayout.readRecord(PageLayout.java:63)
  at PageLayout.readAllRecords(PageLayout.java:73)
```

**Root cause:**

```
1 Page = 1 ByteBuffer (shared)

Thread A: buffer.position(offset_A)   ← sets position
Thread B: buffer.position(offset_B)   ← overwrites position!
Thread A: buffer.getInt()              ← reads from wrong location → UnderflowException
```

```
[Thread A]  position(120) ──────────────────────┐ getInt()
                                                 ↓         ↑ reads wrong location
[Thread B]           position(4088) ─────────────┘
                     (near end of buffer → nothing to read)
```

**Fix:** Remove `position()` calls → use absolute index methods

```java
// Before — shared state (thread-unsafe)
buffer.position(offset);
int valueLength = buffer.getInt();
buffer.get(value);

// After — absolute position (thread-safe)
int valueLength = buffer.getInt(offset);
System.arraycopy(buffer.array(), offset + 4, value, 0, valueLength);
```

> `buffer.getInt(index)` does not modify the internal position.
> Each thread accesses with its own independent offset → no collision.

---

## Bug 2. `CacheManager.getPage()` — duplicate Page object creation

**Symptom:**
```
Threads hold different Page objects for the same pageId
→ Thread A's writes are invisible to Thread B
→ Data loss
```

**Root cause:**

```
Thread A: cache.get(42) → null
Thread B: cache.get(42) → null          ← both see null simultaneously
Thread A: readPage(42)  → Page@0xAAA
Thread B: readPage(42)  → Page@0xBBB    ← different object created
Thread A: cache.put(42, Page@0xAAA)
Thread B: cache.put(42, Page@0xBBB)     ← overwrites!

Result:
  cache → Page@0xBBB
  Thread A → Page@0xAAA (different object)
  → Thread A's writes never reach the cache
```

**Fix:** Use `computeIfAbsent` to atomize the check-then-act

```java
// Before
Page page = cache.get(pageId);
if (page == null) {
    page = diskManager.readPage(pageId);
    cache.put(pageId, page);
}
return page;

// After
return cache.computeIfAbsent(pageId, diskManager::readPage);
```

> `computeIfAbsent` guarantees the mapping function runs at most once per key.
> All threads share the exact same Page object reference.

---

## Bug 3. `writeRecord()` + `readAllRecords()` concurrent access

**Symptom:**
```
GeoIndex results missing under 500 concurrent threads
FullScan: 346 results vs GeoIndex: 335 results (11 missing)
Not reproducible on a single thread → confirmed concurrency bug
```

**Root cause:**

```java
writeRecord():
  1. recordCount = getRecordCount(page)  ← Thread A reads count=5
  2. buffer.putInt(newOffset, value)     ← writing data
  3. setRecordCount(page, count + 1)     ← not yet written (still count=5)

readAllRecords():
  → recordCount = 5                     ← Thread B reads count
  → 6th record exists but count=5 → not read
  → missing!
```

```
[Write thread]  read_count(5) → write_data → write_count(6)
                                    ↑
[Read  thread]                 read_count(5) → reads only 5 → missing
```

**Fix:** `ReentrantReadWriteLock` per `primaryPageId` to separate reads and writes

```java
private final ConcurrentHashMap<Integer, ReentrantReadWriteLock> pageLocks;

private void writeWithOverflow(Page page, byte[] value) {
    ReentrantReadWriteLock.WriteLock writeLock = getLock(page.getPageId()).writeLock();
    writeLock.lock();
    try {
        // traverse full overflow chain + write
    } finally {
        writeLock.unlock();
    }
}

private List<String> readAllCodesFromChain(int pageId) {
    ReentrantReadWriteLock.ReadLock readLock = getLock(pageId).readLock();
    readLock.lock();
    try {
        // read full overflow chain
    } finally {
        readLock.unlock();
    }
}
```

> Compared to `synchronized(page)`: multiple read threads can proceed concurrently → higher read throughput.
> Bug 2's fix (`computeIfAbsent`) guarantees same pageId = same Page object,
> so the lock operates correctly.
> **Bug 2 and Bug 3 must be fixed together.**

---

## Bug 4. `overflowFreeList` — concurrent allocation collision

**Symptom:**
```
Two threads receive the same overflow pageId simultaneously
→ Two different data entries overwrite each other on the same page
→ Data loss
```

**Root cause:**

```java
// ArrayDeque is not thread-safe
private ArrayDeque<Integer> overflowFreeList;

// concurrent pop() → two threads can get the same pageId
private int allocateOverflowPage() {
    return overflowFreeList.pop();  // ← non-atomic
}
```

**Fix:** Replace with `ConcurrentLinkedDeque` + `poll()`

```java
// Before
private ArrayDeque<Integer> overflowFreeList;
return overflowFreeList.pop();

// After
private ConcurrentLinkedDeque<Integer> overflowFreeList;
Integer pageId = overflowFreeList.poll();  // atomic dequeue
if (pageId == null) throw new IllegalStateException("overflow pool exhausted");
return pageId;
```

> `ConcurrentLinkedDeque.poll()` is a lock-free atomic operation.
> Two threads calling it simultaneously are guaranteed to receive different pageIds.

---

## How the lower-layer bugs were discovered

Even after fixing Bugs 1–4, intermittent data loss still reproduced under 500 threads.

```
1. JMeter 500 threads → data loss occurs

2. Reset API (PageCacheStore only) + single thread → still missing
   Even with JVM cache cleared, single-thread still reproduced the issue

3. Tomcat restart + single thread → no missing data
   Full restart clears it → corrupted state is surviving somewhere in the JVM

4. Reset API with CacheManager.clearCache() added + single thread → no missing data
   Corrupted Pages were surviving in the CacheManager buffer, not just PageCacheStore
```

500 threads had corrupted Pages in the CacheManager buffer, and those corrupted Pages were being returned even on a single thread. Clearing the buffer forced a fresh read from disk, returning correct data.

This confirmed that **upper-layer locks do not guarantee thread-safety in lower layers.** Investigation of lower layers revealed Bugs 5–8.

---

## Bug 5. `DiskManager.readPage()` — RandomAccessFile seek/read race

**Symptom:**
```
Thread A's read returns data from Thread B's file position
→ Existing pageId returns empty or wrong data
→ GeoIndex search results missing
```

**Root cause:**

```
RandomAccessFile shares a single internal file pointer (position).

Thread A: dbFile.seek(offset_A)   ← sets position
Thread B: dbFile.seek(offset_B)   ← overwrites position!
Thread A: dbFile.readFully()      ← reads from offset_B → wrong data!
```

```
[Thread A]  seek(1000) ────────────────────────┐ readFully()
                                               ↓         ↑ reads wrong location
[Thread B]          seek(204800) ──────────────┘
```

**Fix:** Add `synchronized` to `readPage()`

```java
// Before — thread-unsafe
public Page readPage(int pageId) { ... }

// After — seek+readFully protected as an atomic block
public synchronized Page readPage(int pageId) { ... }
```

---

## Bug 6. `DiskManager.writePage()` — compound race condition

**Symptom:**
```
Data corruption when multiple pages are added concurrently:
→ Different data written to the same offset
→ Header (pageMap) inconsistent with actual data
```

**Root cause:**

```java
// pageMap is a HashMap — not thread-safe!
private final Map<Integer, Long> pageMap  = new HashMap<>();
private final Map<Integer, Integer> entryIndex = new HashMap<>();
private int entryCount = 0;          // not volatile
private long nextDataOffset = DATA_OFFSET;  // not volatile
```

```
Thread A & B: both write new pageId simultaneously
Both check offset == null
Thread A: reads nextDataOffset = 1000
Thread B: reads nextDataOffset = 1000  ← same value!
Both write different data to offset=1000 → corruption!

Concurrent entryCount++ → header entry lost
Concurrent pageMap.put() → HashMap state corrupted
```

**Fix:** Add `synchronized` to `writePage()`

```java
// Before — compound race condition
public void writePage(Page page) { ... }

// After — new page allocation through write protected atomically
public synchronized void writePage(Page page) { ... }
```

> Both `readPage()` and `writePage()` must be `synchronized` to guarantee
> no file pointer collisions across seek → read/write.

---

## Bug 7. `Page.dirty` — visibility problem

**Symptom:**
```
Thread A calls markDirty(), but Thread B's flush() reads isDirty() = false
→ Dirty page not written to disk → data loss
```

**Root cause:**

```java
private boolean dirty;  // ← not volatile
```

The JVM may cache variables in per-thread CPU caches.
Without `volatile`, a write from one thread may not be immediately visible to another.

**Fix:** Declare as `volatile`

```java
// Before
private boolean dirty;

// After
private volatile boolean dirty;
```

> `volatile` forces writes to main memory immediately and
> forces reads to always fetch from main memory.

---

## Bug 8. `CacheManager.flush()` — dirty flag check-then-act race

**Symptom:**
```
Pages modified during flush() have their changes lost from disk
→ Data loss
```

**Root cause:**

```
Thread A (flush): isDirty() check → true
Thread A:         calls writePage()
Thread B:         modifies same page → calls markDirty()
Thread A:         calls clearDirty() → Thread B's changes are lost!
```

The three operations isDirty() → writePage() → clearDirty() are not atomic.
Another thread can interleave and cause the dirty flag to be cleared prematurely.

**Fix:** Extend the critical section with `synchronized(page)`

```java
// Before
public void flush() {
    for (Page page : cache.values()) {
        if (page.isDirty()) {
            diskManager.writePage(page);
            page.clearDirty();  // ← gap between check and clear
        }
    }
}

// After
public void flush() {
    for (Page page : cache.values()) {
        synchronized (page) {
            if (page.isDirty()) {
                diskManager.writePage(page);
                page.clearDirty();  // ← runs inside atomic block
            }
        }
    }
}
```

---

## Bug 9. `PageCacheStore` — concurrent LinkedHashMap access

**Symptom:**
```
Exceeds maxSize, or LRU order corrupted
→ Hotspot pageIds evicted, or cache structure corrupted
```

**Root cause:**

```
LinkedHashMap is not thread-safe.
In access-order mode, get() modifies the internal order (linked list).

Thread A: pageCache.get(pageId_A)  ← modifying internal order
Thread B: pageCache.get(pageId_B)  ← modifying internal order simultaneously
→ Linked list pointer corruption → structure corrupted

put() alone:
  Thread A & B: both check size() simultaneously → both see below maxSize
  → both call put() → maxSize exceeded
```

**Fix:** Add `synchronized` to all public methods

```java
// Before — ConcurrentHashMap (reads async, writes sync)
private volatile ConcurrentHashMap<Integer, CacheEntry<T>> pageCache;
public PageResult<T> getOrMiss(...) { ... }           // async
public synchronized void put(int pageId, ...) { ... } // sync

// After — LinkedHashMap (all access synchronized)
private final LinkedHashMap<Integer, CacheEntry<T>> pageCache = new LinkedHashMap<>(16, 0.75f, true);
public synchronized PageResult<T> getOrMiss(...) { ... }
public synchronized void put(int pageId, ...) { ... }
public synchronized void clearCache() { ... }
    if (policy.isMaxSizeEnabled() && pageCache.size() >= policy.getMaxSize()) {
        evictOne();
    }
    pageCache.put(pageId, ...);
}
```

---

## Root Cause Analysis

All discovered concurrency bugs share a common structural cause.

```
SpatialRecordManager (Page-level lock ✅)
        ↓
  CacheManager        (flush sync missing ❌ → Bug 8)
        ↓
  DiskManager         (RandomAccessFile sync missing ❌❌❌ → Bug 5, 6)
```

**The core problem:**
- `SpatialRecordManager` protected logical consistency with `synchronized(page)` at the Page object level.
- But the lower layers — `CacheManager` and `DiskManager` — were not thread-safe, causing race conditions at the actual I/O level.
- **Upper-layer locks do not guarantee thread-safety in lower layers.**

---

## Fix Technique Comparison

| Situation | Technique | Reason |
|-----------|-----------|--------|
| Single data structure operation is unsafe | `Concurrent` collections | Per-operation atomicity |
| "get or create" check-then-act | `computeIfAbsent` | Two operations become one atomic operation |
| Multiple operations must be atomic together | `synchronized` | Serialize the entire critical section |
| Read/write at absolute position | `buffer.getInt(index)` | Eliminates shared state (position) entirely |
| Multi-thread visibility guarantee | `volatile` | Bypass CPU cache, force main memory access |

---

## Verification Results

### Verification method

```java
// GET /loadtest/compare?lat=&lng=&radius=
// Same coordinates queried by FullScan / GeoIndex concurrently → compare results

Set<String> fsCodes = fullScanResult.stream().map(code).collect(toSet());
Set<String> giCodes = geoIndexResult.stream().map(code).collect(toSet());

onlyInFs = fsCodes - giCodes  // missing from GeoIndex
onlyInGi = giCodes - fsCodes  // excess in GeoIndex
```

### Before fix (500 threads)
```
lat=37.4902698 lng=127.0051245
FullScan: 5,040 | GeoIndex: 4,610 | Missing: 430 ❌

lat=37.5239346 lng=127.0293712
FullScan: 6,313 | GeoIndex: 5,869 | Missing: 444 ❌
```

### After fix (500 threads)
```
lat=37.5263327 lng=127.0274689
FullScan: 6,460 | GeoIndex: 6,460 | Match: true | Missing: 0 ✅

lat=37.5015093 lng=127.0217788
FullScan: 5,234 | GeoIndex: 5,234 | Match: true | Missing: 0 ✅

lat=37.5173572 lng=127.0234091
FullScan: 5,765 | GeoIndex: 5,765 | Match: true | Missing: 0 ✅
```

> **0 data loss** from concurrency under 500 concurrent threads achieved.

---

## Bug 10. `search(batchLoader)` — missing double-check after `putIfAbsent` winner

**Symptom:**
```
Loader called twice for the same pageId
→ Duplicate DB queries (data correctness maintained, efficiency degraded)
```

**Root cause:**

```
Goal: loader must be called at most once per pageId.

Thread A: getOrMiss(pageId) → MISS
Thread A: putIfAbsent(pageId, futureA) → null (winner)
Thread A: starts loader
Thread A: pageCacheStore.put(pageId, data)   ← stored
Thread A: future.complete(data)
Thread A: pendingLoads.remove(pageId)        ← removed

Thread B: getOrMiss(pageId) → MISS  (checked before Thread A's remove)
Thread B: putIfAbsent(pageId, futureB) → null (winner)  ← A already removed it
Thread B: starts loader   ← duplicate call!
```

```
[Thread A]  getOrMiss(MISS) → putIfAbsent(winner) → loader → put → complete → remove
                                                                                   ↑
[Thread B]                                     getOrMiss(MISS) ─────────────────── putIfAbsent(winner) → loader duplicate!
```

After Thread A completes put→complete→remove, Thread B calls putIfAbsent on an empty pendingLoads and becomes winner. Since getOrMiss already returned MISS, the loader is called again.

**Fix:** Add `getOrMiss` double-check immediately after `putIfAbsent`

```java
// Right after winning putIfAbsent — another thread may have already put
CompletableFuture<List<T>> existing = pendingLoads.putIfAbsent(pageId, future);
if (existing == null) {
    // double-check: I may have become winner right after Thread A's put+remove cycle
    PageResult<T> recheck = pageCacheStore.getOrMiss(pageId, codes);
    if (recheck.isHit()) {
        future.complete(recheck.getCached());
        pendingLoads.remove(pageId);
        hitResults.put(pageId, recheck.getCached());
    } else {
        toLoad.put(pageId, codes);   // actual loading needed
        myFuture.put(pageId, future);
    }
} else {
    waitFuture.put(pageId, existing);  // waiter
}
```

> The double-check re-validates the cache immediately after becoming winner.
> It handles the fact that "becoming winner may mean another thread just finished and removed."

---

## Modified Files

| File | Change | Bug |
|------|--------|-----|
| `storage/PageLayout.java` | Remove `position()` → use absolute index methods | Bug 1 |
| `buffer/CacheManager.java` | `getPage()` → `computeIfAbsent` | Bug 2 |
| `api/SpatialRecordManager.java` | `writeWithOverflow()`, `readAllCodesFromChain()` → `ReentrantReadWriteLock` read/write separation | Bug 3 |
| `api/SpatialRecordManager.java` | `overflowFreeList` → `ConcurrentLinkedDeque` | Bug 4 |
| `storage/DiskManager.java` | `readPage()` → `synchronized` | Bug 5 |
| `storage/DiskManager.java` | `writePage()` → `synchronized` | Bug 6 |
| `storage/Page.java` | `dirty` → `volatile boolean dirty` | Bug 7 |
| `buffer/CacheManager.java` | `flush()` → add `synchronized(page)` block | Bug 8 |
| `cache/PageCacheStore.java` | `ConcurrentHashMap` → `LinkedHashMap(access-order)` + all methods `synchronized` (LRU eviction) | Bug 9 |
| `index/GeoHashIndex.java` | `getPageIds()` boundary → `Math.min((1L<<15)-1, ...)` clamping | Logic bug |
| `api/SpatialCacheEngine.java` | `search(batchLoader)` — add double-check after `putIfAbsent` | Bug 10 |
