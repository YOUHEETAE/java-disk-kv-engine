# Storage Module

Disk-based page storage layer.

## 📦 Classes

### `Page.java`

Represents a single 4KB page in memory.

**Structure:**
- Fixed size: 4096 bytes
- Contains header, slots, and records
- Dirty flag for Write-Back caching

**Key Methods:**
```java
int getPageId()          // Get page number
byte[] getData()         // Get raw byte array
ByteBuffer buffer()      // Get ByteBuffer wrapper
boolean isDirty()        // Check if modified
void markDirty()         // Mark as modified
void clearDirty()        // Clear dirty flag
```

**Usage:**
```java
Page page = new Page(0);
page.markDirty();
byte[] data = page.getData();
```

---

### `DiskManager.java`

Handles physical disk I/O operations.

**Responsibilities:**
- Read pages from disk
- Write pages to disk
- Manage file handle

**Key Methods:**
```java
Page readPage(int pageId)    // Read from disk
void writePage(Page page)    // Write to disk
void close()                 // Close file handle
```

**File Layout:**
```
Offset = pageId × 4096

Page 0: bytes 0-4095
Page 1: bytes 4096-8191
Page 2: bytes 8192-12287
...
```

**Usage:**
```java
DiskManager dm = new DiskManager("data.db");

// Read
Page page = dm.readPage(5);

// Write
dm.writePage(page);

// Close
dm.close();
```

---

## 🔑 Key Concepts

**Page-based Storage:**
- Disk I/O operates in fixed-size chunks
- Reduces number of disk operations
- Aligns with OS page size

**Why 4KB?**
- Common OS page size
- Good balance between overhead and utilization
- Industry standard (MySQL, PostgreSQL use 8KB-16KB)

---

## 📊 Trade-offs

| Aspect | Choice | Reason |
|--------|--------|--------|
| **Page Size** | 4KB | Simple, OS-aligned |
| **File Format** | Sequential pages | Easy random access |
| **Buffering** | None (here) | Handled by CacheManager |

---

## 🔗 Dependencies

- `java.io.RandomAccessFile` - File I/O
- `java.nio.ByteBuffer` - Memory management