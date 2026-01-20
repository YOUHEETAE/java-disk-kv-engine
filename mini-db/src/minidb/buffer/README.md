# Buffer Module

In-memory page caching layer with Write-Back strategy.

## 📦 Classes

### `CacheManager.java`

Manages page cache between API layer and disk.

**Responsibilities:**
- Cache pages in memory (unlimited for now)
- Write-Back caching (defer disk writes)
- Batch flush dirty pages

**Key Methods:**
```java
Page getPage(int pageId)     // Get from cache or disk
void putPage(Page page)      // Mark dirty, don't write
void flush()                 // Write all dirty pages
void close()                 // Flush and close disk
```

**Cache Strategy:**
```
┌─────────┐
│ getPage │ → Check cache → Hit? Return
│         │              → Miss? Read from disk, cache, return
└─────────┘

┌─────────┐
│ putPage │ → Mark dirty
│         │ → Store in cache
│         │ → Don't write to disk!
└─────────┘

┌─────────┐
│  flush  │ → For each dirty page:
│         │     Write to disk
│         │     Clear dirty flag
└─────────┘
```

---

## 🔑 Key Concepts

**Write-Back vs Write-Through:**

| Strategy | Behavior | Performance |
|----------|----------|-------------|
| **Write-Through** | Write to disk immediately | Slow (every write = disk I/O) |
| **Write-Back** | Write to memory, flush later | Fast (batched disk I/O) |

**Current Implementation:**
- ✅ Write-Back enabled
- ✅ Manual flush (call `flush()` or `close()`)
- ❌ No size limit (unlimited cache)
- ❌ No eviction policy (yet)

---

## 📈 Performance Impact

**Before (Write-Through):**
```
100 writes → 100 disk I/O → 500ms
```

**After (Write-Back):**
```
100 writes → 100 memory writes → 50ms
1 flush → 1 batch disk I/O → 50ms
Total: 100ms
```

---

## 🚧 Future Improvements

**Buffer Pool (planned):**
- Limit cache size (e.g., 100 pages)
- LRU eviction policy
- Evict dirty pages → write to disk

**Example:**
```java
// Future API
CacheManager cache = new CacheManager(diskManager, 100); // Max 100 pages

cache.getPage(1000); // If cache full, evict LRU page
```

---

## 🔗 Dependencies

- `minidb.storage.DiskManager` - Disk operations
- `minidb.storage.Page` - Page objects
- `java.util.HashMap` - Cache storage# Buffer Module
