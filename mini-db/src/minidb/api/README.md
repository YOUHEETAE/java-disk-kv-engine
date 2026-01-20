# API Module

Key-Value interface with slotted page management.

## 📦 Classes

### `RecordManager.java`

Provides Key-Value API on top of page storage.

**Responsibilities:**
- Key-Value operations (put/get)
- Slotted page management
- Overflow chain traversal
- Page initialization

**Public API:**
```java
void put(String key, byte[] value)    // Store
byte[] get(String key)                // Retrieve
```

---

## 🔧 Internal Structure

### Page Header (16 bytes)
```
Position 0-3:   recordCount      (int)
Position 4-7:   freeSpaceStart   (int)
Position 8-11:  magic            (int, 0xCAFEBABE)
Position 12-15: overflowPageId   (int, -1 = none)
```

### Slot Directory (8 bytes per slot)
```
Slot N: [offset (4 bytes)][length (4 bytes)]
```

### Record Format
```
[keyLength (4)][key bytes][valueLength (4)][value bytes]
```

---

## 🔑 Key Concepts

**Hash-based Page Mapping:**
```java
pageId = Math.abs(key.hashCode() % 1000)
```

**Collision Handling:**
- Multiple records per page (Slotted Page)
- Overflow chaining when page is full

**Overflow Chain:**
```
Page 890 (full, 30 records)
  overflowPageId = 1234
    ↓
Page 1234 (10 records)
  overflowPageId = 5678
    ↓
Page 5678 (5 records)
  overflowPageId = -1 (end)
```

---

## 📊 Operations

### `put(key, value)`
```
1. pageId = hash(key) % 1000
2. page = getPage(pageId)
3. if (!initialized) → initializePage()
4. writeRecord(page, key, value)
   - Check space
   - If full → allocate overflow page
   - Write to slot
   - Update header
5. putPage(page) → mark dirty
```

### `get(key)`
```
1. pageId = hash(key) % 1000
2. page = getPage(pageId)
3. readRecord(page, key)
   - Check initialized
   - Search current page (reverse order)
   - If not found → follow overflow chain
   - Return value or null
```

---

## 🛡️ Safety Mechanisms

**Initialization Check:**
```java
if (!isInitialized(page)) {
    return null; // Uninitialized = no data
}
```

**Self-reference Prevention:**
```java
if (overflowPageId != -1 && overflowPageId != page.getPageId()) {
    // Prevent infinite recursion
}
```

**Magic Number:**
```
0xCAFEBABE = initialized page
0x00000000 = uninitialized page
```

---

## 📈 Performance Characteristics

| Operation | Current | With Index (Future) |
|-----------|---------|---------------------|
| **put()** | O(1) avg | O(1) |
| **get()** | O(n) slots | O(1) |
| **Space** | ~90% util | ~90% util |

**Current Limitations:**
- No index → O(n) slot scan
- No deletion support
- No update detection (duplicate keys create new slots)

---

## 🚧 Future Improvements

**RecordId (next):**
```java
class RecordId {
    int pageId;
    int slotId;
}

// Direct slot access → O(1) read
```

**Hash Index:**
```java
Map<String, RecordId> index;

// put() → store RecordId in index
// get() → lookup RecordId, direct access
```

**Deletion:**
```java
void delete(String key);
// Mark slot as deleted
// Compact page periodically
```

---

## 🔗 Dependencies

- `minidb.buffer.CacheManager` - Page caching
- `minidb.storage.Page` - Page structure
- `java.nio.ByteBuffer` - Byte manipulation# API Module
