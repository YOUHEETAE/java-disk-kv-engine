# API 모듈

공간 인덱스 기반 저장/검색 엔진 — 최상단 API 레이어

---

## 클래스

### SpatialRecordManager.java

공간 인덱스를 통해 좌표 기반으로 레코드를 저장하고 반경 검색을 수행합니다.

**API:**
```java
void put(double lat, double lng, byte[] value)
void rebuild(Consumer<SpatialRecordManager> loader)

List<byte[]> searchRadius(double lat, double lng, double radiusKm)
List<String> searchRadiusCodes(double lat, double lng, double radiusKm)

Map<Integer, List<String>> searchRadiusCodesByPageId(double lat, double lng, double radiusKm)
List<String> getAllCodesByPageId(int pageId)
```

| 메서드 | 반환 | 용도 |
|--------|------|------|
| `put` | void | 파일에 병원 코드 저장 |
| `rebuild` | void | 파일 재구축 + pageLocks 초기화 |
| `searchRadius` | `List<byte[]>` | 더미 데이터 벤치마크 |
| `searchRadiusCodes` | `List<String>` | 병원 코드 목록 |
| `searchRadiusCodesByPageId` | `Map<pageId, List<String>>` | 캐시 HIT/MISS 분기 |
| `getAllCodesByPageId` | `List<String>` | MISS pageId 전체 codes 조회 |

---

### SpatialCacheEngine.java

`SpatialRecordManager` 위에서 HIT/MISS 판단과 JVM 캐시 관리를 담당합니다.

**API:**
```java
List<PageResult<T>> search(double lat, double lng, double radiusKm)
void putCache(int pageId, List<T> data)

void rebuild(Consumer<SpatialRecordManager> loader)
void clearCache()

boolean isCached(int pageId)
long getCacheSize()
CachePolicy getPolicy()
```

| 메서드 | 반환 | 용도 |
|--------|------|------|
| `search` | `List<PageResult<T>>` | HIT/MISS 판단 후 반환 |
| `putCache` | void | MISS 후 DB 결과 JVM 캐시 저장 |
| `rebuild` | void | 파일 재구축 + JVM 캐시 초기화 |
| `clearCache` | void | JVM 캐시만 초기화 |
| `isCached` | boolean | 특정 pageId 캐시 여부 확인 |
| `getCacheSize` | long | 현재 캐시 항목 수 |

---

### SpatialCache.java

`SpatialCacheEngine`이 구현하는 인터페이스입니다.

```java
interface SpatialCache<T> {
    List<PageResult<T>> search(double lat, double lng, double radiusKm);
    void put(List<T> data);
}
```

---

## Phase 12: ReentrantReadWriteLock 동시 접근 제어

### 왜 이 엔진은 동시성이 특히 중요한가

```
일반 캐시:
  잘못된 캐시 → TTL 만료 후 자동 복구 ✅

이 엔진:
  put() → pageId 계산 → Page에 영구 기록
  한번 잘못 기록된 Page = rebuild() 전까지 영원히 틀린 결과
  데이터 정확성 = 생명
```

### Bug 3: writeRecord + readAllCodesFromChain 동시 접근

```
writeRecord():
  1. recordCount 읽기  (count = 5)
  2. 데이터 기록 중
  3. recordCount 증가  (count = 6) ← 아직 안 씀

readAllCodesFromChain():
  → recordCount = 5 읽음            ← 2번과 3번 사이에 진입
  → 5개만 읽음 → 6번째 레코드 누락
```

```
[Write]  read_count(5) ──── write_data ──── write_count(6)
                                 ↑
[Read ]              read_count(5) → 5개만 읽음 → 누락
```

**결과:** 스레드 500 동시 요청 시 FullScan 346건 vs GeoIndex 335건 (11건 누락)

### 해결

`primaryPageId` 단위의 `ReentrantReadWriteLock`으로 읽기/쓰기를 분리했다. 쓰기는 WriteLock, 읽기는 ReadLock을 획득하며, primaryPage 락 하나가 전체 overflow 체인을 보호한다.

```java
private final ConcurrentHashMap<Integer, ReentrantReadWriteLock> pageLocks;

private ReentrantReadWriteLock getLock(int pageId) {
    return pageLocks.computeIfAbsent(pageId, k -> new ReentrantReadWriteLock());
}

private void writeWithOverflow(Page page, byte[] value) {
    int primaryPageId = page.getPageId();
    ReentrantReadWriteLock.WriteLock writeLock = getLock(primaryPageId).writeLock();
    writeLock.lock();
    try {
        // 전체 overflow 체인 순회 + 기록
    } finally {
        writeLock.unlock();
    }
}

private List<String> readAllCodesFromChain(int pageId) {
    ReentrantReadWriteLock.ReadLock readLock = getLock(pageId).readLock();
    readLock.lock();
    try {
        // overflow 체인 전체 읽기
        // overflow 페이지는 별도 락 없이 읽음 — primaryPage 락이 보호
    } finally {
        readLock.unlock();
    }
}
```

`synchronized(page)` 대비 장점: 읽기 요청이 동시에 여러 스레드에서 진행 가능하여 읽기 처리량이 향상된다.

---

### Bug 4: overflowFreeList 동시 할당

```
ArrayDeque.pop() 동시 호출
→ 두 스레드가 같은 overflow pageId 반환
→ 두 개의 서로 다른 데이터가 같은 페이지에 덮어씀
→ 데이터 유실
```

```java
// Before — thread-unsafe
private ArrayDeque<Integer> overflowFreeList;
return overflowFreeList.pop();

// After — 원자적 꺼내기
private ConcurrentLinkedDeque<Integer> overflowFreeList;
Integer pageId = overflowFreeList.poll();
```

`ConcurrentLinkedDeque.poll()`은 락-프리 원자 연산이다. 두 스레드가 동시에 호출해도 서로 다른 pageId를 반환함이 보장된다.

> 자세한 내용은 [CONCURRENCY.md](../../../../../CONCURRENCY.md) Bug 3, Bug 4 참고

---

### 검증 결과 (스레드 500)

```
비교 lat=37.5263327 lng=127.0274689
FullScan: 6460건 | GeoIndex: 6460건 | 일치: true | 누락: 0건 ✅

비교 lat=37.5015093 lng=127.0217788
FullScan: 5234건 | GeoIndex: 5234건 | 일치: true | 누락: 0건 ✅

전체 100건 비교 → 동시성으로 인한 누락 0건 ✅
```

---

### PageResult\<T\> (값 객체)

`search()` 결과를 pageId 단위로 HIT/MISS 분리해서 표현합니다.

```java
PageResult.hit(pageId, List<T> cached)         // HIT: 캐시 데이터 포함
PageResult.miss(pageId, List<String> codes)    // MISS: DB 조회 필요 codes 포함

result.isHit()       // HIT 여부
result.getPageId()   // 해당 pageId
result.getCached()   // HIT 시 캐시 데이터
result.getCodes()    // MISS 시 hospital_code 목록
```

---

### RecordManager.java

Key-Value 기반 저장소입니다. O(1) 직접 접근을 위한 인메모리 인덱스를 사용합니다.

```java
void put(String key, byte[] value)
byte[] get(String key)
List<byte[]> getAllValues()
```

---

### RecordId.java

레코드의 물리적 위치를 나타내는 값 객체입니다.

```java
class RecordId {
    int pageId;
    int slotId;  // O(1) 직접 접근
}
```

`equals` / `hashCode` 구현 → HashMap 키로 사용 가능 → O(n) 슬롯 스캔에서 O(1) 직접 접근으로 개선

---

## 핵심 설계: 두 티어 페이지 관리

```
PRIMARY 페이지:  GeoHash → pageId 직접 매핑
OVERFLOW 페이지: PRIMARY 꽉 참 → 연결 리스트로 확장

PRIMARY_PAGES = 32,768
OVERFLOW_PAGES = 40,960 (ConcurrentLinkedDeque로 풀 관리)
```

`search()`가 단방향 의존성을 유지하는 이유:
```
기존: SpatialCacheEngine → SpatialRecordManager (순환 위험)
현재: SpatialCacheEngine.search() → spatialRecordManager.searchRadiusCodesByPageId() 위임
     SpatialRecordManager는 SpatialCacheEngine을 모름
     → 단방향 의존성 유지
```