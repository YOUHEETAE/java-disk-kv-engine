# MiniDB 동시성 이슈 해결 보고서

## 왜 이 엔진은 동시성이 특히 중요한가

```
일반 DB 캐시의 오류:
  캐시 MISS → DB 재조회 → 항상 최신 데이터 반환
  → 잘못된 캐시는 TTL 만료 후 자동 복구

MiniDB의 구조적 차이:
  put(lat, lng, hospitalCode) → pageId 계산 → Page에 영구 기록
  search(lat, lng, radius)   → pageId → Page 직접 읽기

  한번 잘못 기록된 Page = 영원히 틀린 검색 결과
  캐시 초기화(rebuild) 전까지 복구 불가능
```

**데이터 정확성 = 이 엔진의 생명**

---

## 발견된 동시성 버그 4가지

### Bug 1. `ByteBuffer.position()` 공유 충돌

**증상:**
```
java.nio.BufferUnderflowException
  at PageLayout.readRecord(PageLayout.java:63)
  at PageLayout.readAllRecords(PageLayout.java:73)
```

**원인:**

```
Page 1개 = ByteBuffer 1개 (공유)

스레드 A: buffer.position(offset_A)   ← 포지션 설정
스레드 B: buffer.position(offset_B)   ← 포지션 덮어씀
스레드 A: buffer.getInt()              ← 엉뚱한 위치 읽기 → UnderflowException
```

```
[Thread A]  position(120) ──────────────────────┐ getInt()
                                                 ↓         ↑ 틀린 위치 읽음
[Thread B]           position(4088) ─────────────┘
                     (버퍼 끝 근처 → 읽을 데이터 없음)
```

**해결:** `position()` 제거 → 절대 위치 메서드 사용

```java
// Before - 상태 공유 (thread-unsafe)
buffer.position(offset);
int valueLength = buffer.getInt();
buffer.get(value);

// After - 절대 위치 (thread-safe)
int valueLength = buffer.getInt(offset);
System.arraycopy(buffer.array(), offset + 4, value, 0, valueLength);
```

> `buffer.getInt(index)` 는 내부 position을 변경하지 않는다.
> 각 스레드가 독립적인 오프셋으로 접근 → 충돌 없음.

---

### Bug 2. `CacheManager.getPage()` 중복 Page 객체 생성

**증상:**
```
같은 pageId에 대해 스레드마다 다른 Page 객체 보유
→ 스레드 A가 write한 내용을 스레드 B는 모름
→ 데이터 유실
```

**원인:**

```
스레드 A: cache.get(42) → null
스레드 B: cache.get(42) → null          ← 동시에 null 확인
스레드 A: readPage(42)  → Page@0xAAA
스레드 B: readPage(42)  → Page@0xBBB    ← 다른 객체 생성
스레드 A: cache.put(42, Page@0xAAA)
스레드 B: cache.put(42, Page@0xBBB)     ← 덮어씀

결과:
  cache → Page@0xBBB
  스레드 A → Page@0xAAA (다른 객체)
  → 스레드 A의 write는 캐시에 반영 안 됨
```

**해결:** `computeIfAbsent`으로 check-then-act 원자화

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

> `computeIfAbsent`는 같은 key에 대해 mapping function을 단 한 번만 실행한다.
> 모든 스레드가 동일한 Page 객체 참조를 공유하게 된다.

---

### Bug 3. `writeRecord()` + `readAllRecords()` 동시 접근

**증상:**
```
스레드 500 동시 요청 시 GeoIndex 결과 누락
FullScan 346건 vs GeoIndex 335건 (11건 누락)
단독 스레드에서는 재현 안 됨 → 동시성 버그 확정
```

**원인:**

```java
writeRecord():
  1. recordCount = getRecordCount(page)  ← 스레드 A: count=5
  2. buffer.putInt(newOffset, value)     ← 데이터 기록 중
  3. setRecordCount(page, count + 1)     ← 아직 안 씀 (count=5 상태)

readAllRecords():
  → recordCount = 5                     ← 스레드 B: count 읽음
  → 6번째 레코드 존재하지만 count=5 → 읽지 않음
  → 누락!
```

```
[Write 스레드]  read_count(5) → write_data → write_count(6)
                                    ↑
[Read  스레드]                 read_count(5) → 5개만 읽음 → 누락
```

**해결:** `synchronized(page)`로 임계구역 설정

```java
private void writeWithOverflow(Page page, byte[] value) {
    synchronized (page) {
        int slotId = PageLayout.writeRecord(page, value);
        if (slotId == -1) {
            // overflow 처리 (동일하게 synchronized)
        }
    }
}

private List<String> readAllCodesFromChain(int pageId) {
    Page page = cacheManager.getPage(pageId);
    synchronized (page) {
        // 읽기 로직
    }
}
```

> Page 객체 자체를 락으로 사용한다.
> Bug 2 해결(computeIfAbsent)로 같은 pageId = 같은 Page 객체가 보장되므로
> synchronized(page)가 올바르게 동작한다.
> **Bug 2와 Bug 3은 반드시 함께 해결해야 한다.**

---

### Bug 4. `overflowFreeList` 동시 할당 충돌

**증상:**
```
같은 overflow pageId를 두 스레드가 동시에 할당받음
→ 두 개의 서로 다른 데이터가 같은 페이지에 덮어씀
→ 데이터 유실
```

**원인:**

```java
// ArrayDeque는 thread-safe하지 않음
private ArrayDeque<Integer> overflowFreeList;

// 동시 pop() → 두 스레드가 같은 pageId 반환 가능
private int allocateOverflowPage() {
    return overflowFreeList.pop();  // ← 비원자적
}
```

**해결:** `ConcurrentLinkedDeque` + `poll()`

```java
// Before
private ArrayDeque<Integer> overflowFreeList;
return overflowFreeList.pop();

// After
private ConcurrentLinkedDeque<Integer> overflowFreeList;
Integer pageId = overflowFreeList.poll();  // 원자적 꺼내기
if (pageId == null) throw new IllegalStateException("overflow pool exhausted");
return pageId;
```

> `ConcurrentLinkedDeque.poll()`은 락-프리(lock-free) 원자 연산이다.
> 두 스레드가 동시에 호출해도 서로 다른 pageId를 반환함이 보장된다.

---

## 해결 기법 비교

| 상황 | 기법 | 이유 |
|------|------|------|
| 자료구조 연산 하나가 unsafe | `Concurrent` 컬렉션 | 연산 단위 원자성 |
| "없으면 생성" check-then-act | `computeIfAbsent` | 두 연산을 하나의 원자 연산으로 |
| 여러 연산이 묶여서 원자적이어야 함 | `synchronized` | 임계구역 전체를 직렬화 |
| 절대 위치로 읽기/쓰기 | `buffer.getInt(index)` | 상태(position) 공유 자체를 제거 |

---

## 검증 결과

### 검증 방법

```java
// GET /loadtest/compare?lat=&lng=&radius=
// 동일 좌표를 FullScan / GeoIndex 동시 조회 → 결과 비교

Set<String> fsCodes = fullScanResult.stream().map(코드).collect(toSet());
Set<String> giCodes = geoIndexResult.stream().map(코드).collect(toSet());

onlyInFs = fsCodes - giCodes  // GeoIndex 누락
onlyInGi = giCodes - fsCodes  // GeoIndex 초과
```

### 수정 전 (스레드 500)
```
비교 lat=37.4902698 lng=127.0051245
FullScan: 5040건 | GeoIndex: 4610건 | FS누락: 430건 ❌

비교 lat=37.5239346 lng=127.0293712
FullScan: 6313건 | GeoIndex: 5869건 | FS누락: 444건 ❌

```

### 수정 후 (스레드 500)
```
비교 lat=37.5263327 lng=127.0274689
FullScan: 6460건 | GeoIndex: 6460건 | 일치: true | FS누락: 0건 ✅

비교 lat=37.5015093 lng=127.0217788
FullScan: 5234건 | GeoIndex: 5234건 | 일치: true | FS누락: 0건 ✅

비교 lat=37.5173572 lng=127.0234091
FullScan: 5765건 | GeoIndex: 5765건 | 일치: true | FS누락: 0건 ✅

```

> 스레드 500 동시 요청 환경에서 동시성으로 인한 데이터 누락 **0건** 달성

---


---

## 수정 파일 목록

| 파일 | 수정 내용 |
|------|----------|
| `storage/PageLayout.java` | `position()` 제거 → 절대 위치 메서드 |
| `buffer/CacheManager.java` | `getPage()` → `computeIfAbsent` |
| `api/SpatialRecordManager.java` | `writeWithOverflow()`, `readAllCodesFromChain()` → `synchronized(page)` |
| `api/SpatialRecordManager.java` | `overflowFreeList` → `ConcurrentLinkedDeque` |