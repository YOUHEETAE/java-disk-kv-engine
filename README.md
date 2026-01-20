# mini-db

A minimal single-node key-value database engine built for learning
how real databases work internally.

## 🎯 Goal
- Understand why databases use pages
- Learn slotted page structure and overflow chaining
- Implement caching strategies (Write-Back)
- (Planned) Build B+Tree index
- (Planned) Experience buffer pool and WAL design trade-offs

## 📦 Scope
- Key-Value store
- Single process / single node
- No SQL, no optimizer, no concurrency (yet)

## 🛠️ Tech
- Java 21
- File-based storage (RandomAccessFile)
- No external dependencies

---

## 🏗️ Architecture

3-Layer Design:
```
┌─────────────────────────────┐
│   API Layer (RecordManager)  │  Key-Value interface
├─────────────────────────────┤
│  Buffer Layer (CacheManager) │  Write-Back caching
├─────────────────────────────┤
│ Storage Layer (DiskManager)  │  Page-based disk I/O
└─────────────────────────────┘
```

---

## ✅ Current Features

**Storage Engine**
- [x] Page-based storage (4KB pages)
- [x] Slotted page structure (multiple records per page)
- [x] Write-Back caching (5-10x faster writes)
- [x] Page overflow chaining (handles hash collisions)

**API**
- [x] `put(key, value)` - Store key-value pair
- [x] `get(key)` - Retrieve value by key

---

## 🚧 Roadmap

**Phase 1: Core Storage** ✅
- [x] DiskManager, Page, CacheManager
- [x] Slotted Page with overflow

**Phase 2: Indexing** (Next)
- [ ] RecordId (pageId, slotId)
- [ ] In-memory hash index
- [ ] B+Tree index

**Phase 3: Advanced**
- [ ] Buffer Pool with LRU eviction
- [ ] Write-Ahead Logging (WAL)
- [ ] Crash recovery
- [ ] Transactions (optional)

---

## 📊 Page Structure
```
Page (4096 bytes):
┌──────────────────────────────────┐
│ Header (16 bytes)                │
│  [0-3]   recordCount             │
│  [4-7]   freeSpaceStart          │
│  [8-11]  magic (0xCAFEBABE)      │
│  [12-15] overflowPageId          │
├──────────────────────────────────┤
│ Slot Directory (8 bytes each)    │
│  Slot 0: [offset][length]        │
│  Slot 1: [offset][length]        │
├──────────────────────────────────┤
│ Free Space (grows down)          │
├──────────────────────────────────┤
│ Records (grow up from end)       │
│  Record N, ..., Record 1, Record 0│
└──────────────────────────────────┘
```

**Overflow Chaining:**
```
Page 890 (full) → Page 1234 → Page 5678 → -1 (end)
```

---

## 🚀 Usage
```java
// Setup
DiskManager diskManager = new DiskManager("data.db");
CacheManager cacheManager = new CacheManager(diskManager);
RecordManager recordManager = new RecordManager(cacheManager);

// Write
recordManager.put("user:1001", "Alice".getBytes());
recordManager.put("user:1002", "Bob".getBytes());

// Read
byte[] value = recordManager.get("user:1001");
System.out.println(new String(value)); // "Alice"

// Flush and close
cacheManager.close();
```

---

## 📈 Performance

| Operation | Complexity | Notes |
|-----------|-----------|-------|
| **Write** | O(1) avg | Memory-only (Write-Back) |
| **Read** | O(n) slots | No index yet |
| **Space** | ~90% util | Slotted page efficiency |
| **Collision** | O(k) chain | Overflow chaining |

---

## 🎓 Learning Points

**Why pages?**
- Disk I/O is expensive → read/write in chunks
- OS also uses pages → alignment benefits

**Why slotted pages?**
- Variable-length records need flexible layout
- Header + directory + records = efficient space usage

**Why Write-Back?**
- Batching writes reduces disk I/O
- Trade-off: requires flush on crash

**Why overflow chaining?**
- Hash collisions are inevitable
- Chaining > rehashing for simplicity

---

## 📝 License

MIT - Educational project