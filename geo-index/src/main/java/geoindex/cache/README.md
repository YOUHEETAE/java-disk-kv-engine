# Cache 모듈

pageId 단위 JVM 캐시 인프라 — Spring 없이 순수 Java 제네릭으로 구현

---

## 왜 엔진 레벨에 캐시가 필요한가

```
기존 Redis Geohash 방식:
  요청 → Redis MGET(9개 격자) → 네트워크 왕복
  MISS → MariaDB 조회 → Redis 저장 → 재반환

이 모듈:
  요청 → LinkedHashMap.get(pageId) → 나노초
  MISS → MariaDB 조회 → JVM 메모리 저장 → 재반환
  네트워크 왕복 없음, 외부 인프라 불필요
```

---

## 클래스

### CachePolicy

```java
CachePolicy.DEFAULT           // TTL_DISABLE + maxSize 무제한
CachePolicy.builder()
    .ttl(Duration.ofDays(7))
    .maxSize(5000)
    .build()
```

TTL 비활성화(기본): 배치 업데이트 시 rebuild()로 명시적 초기화
TTL 활성화: Spring @Value로 주입 가능 (`cache.ttl.days=7`)

---

### CacheEntry\<T\>

```java
List<T> data          // 캐시 데이터
Instant expiresAt     // null = TTL_DISABLE

isExpired()                      // expiresAt == null → false, 현재시각 > expiresAt → true
CacheEntry.of(data)              // TTL 없음
CacheEntry.of(data, expiresAt)   // TTL 있음
```

---

### PageCacheStore\<T\>

pageId 단위 LRU 캐시 인프라.
TTL 만료 체크, maxSize 초과 시 LRU evict, clearCache를 담당합니다.

```java
PageResult<T> getOrMiss(int pageId, List<String> codes)
void put(int pageId, List<T> data)
void clearCache()
long getCacheSize()
boolean isCached(int pageId)
CachePolicy getPolicy()
```

**getOrMiss(pageId, codes):**
```
pageCache.get(pageId)             ← LinkedHashMap access-order → 접근 시 tail로 이동 (LRU 갱신)
  → null 또는 isExpired() → pageCache.remove(pageId) → PageResult.miss(pageId, codes)
  → 유효 → PageResult.hit(pageId, cached)
```

**put(pageId, List\<T\>):**
```
maxSize 초과 시 → evictOne() (LRU head = 가장 오래전 접근 항목 제거)
pageCache.put(pageId, CacheEntry.of(data)) → 덮어쓰기

덮어쓰기를 선택한 이유:
  같은 pageId = 같은 DB 조회 = 같은 데이터
  Thundering Herd 시 동일 pageId 동시 put
  → addAll이면 중복 데이터 쌓임 ❌
  → 덮어쓰기면 결과 동일, 중복 없음 ✅
```

**clearCache():**
```
pageCache.clear()  ← 내용물만 비움 (참조는 final로 고정)
```

---

## Thread-safety

모든 공개 메서드(`getOrMiss`, `put`, `clearCache`, `isCached`, `getCacheSize`)가 `synchronized`로 보호됨.

`LinkedHashMap`은 thread-safe하지 않으며, access-order 모드에서 `get()` 호출 시 내부 순서가 변경된다. 읽기(`getOrMiss`)도 순서를 변경하므로 `synchronized` 없이는 동시 접근 시 구조가 손상될 수 있다.

`put()`의 `synchronized`는 추가로 maxSize 체크 → evict → put의 원자성을 보장한다.
→ [CONCURRENCY.md Bug 9 참고](../../../../../CONCURRENCY.md)

---

## LRU eviction

```java
// LinkedHashMap(initialCapacity, loadFactor, accessOrder=true)
private final LinkedHashMap<Integer, CacheEntry<T>> pageCache = new LinkedHashMap<>(16, 0.75f, true);

// evictOne: head = 가장 오래전에 접근한 항목
private void evictOne() {
    Integer victim = pageCache.keySet().iterator().next();
    pageCache.remove(victim);
}
```

`accessOrder=true`로 생성된 LinkedHashMap은 `get()` 호출 시 해당 항목을 tail로 이동시킨다.
→ iterator().next()는 항상 가장 오래전에 접근한 항목(LRU)을 반환한다.

```
초기: [강남, 강북, 홍대]
강남 getOrMiss() 호출 →
이후: [강북, 홍대, 강남]  ← 강남 tail로 이동
evict → 강북 제거 (head = 가장 오래전 접근)
```

현재 기본값은 maxSize UNLIMITED이므로 eviction은 발생하지 않는다.
→ 핫스팟 pageId가 절대 날아가지 않음

---

## SpatialCacheEngine (api/) — PageCacheStore 사용처

`PageCacheStore`를 직접 사용하는 파사드입니다. HIT/MISS 판단과 JVM 캐시 관리를 담당합니다.

```java
// SpatialCacheEngine이 PageCacheStore를 사용하는 흐름
Map<Integer, List<String>> codesByPageId = spatialRecordManager.searchRadiusCodesByPageId(...);
PageResult<T> result = pageCacheStore.getOrMiss(pageId, codes);  // HIT/MISS 판단

pageCacheStore.put(pageId, data);    // MISS 후 DB 결과 저장
pageCacheStore.clearCache();         // rebuild() 시 JVM 캐시 초기화
```

`SpatialCacheEngine`은 `SpatialRecordManager`를 알지만, `PageCacheStore`는 둘 다 모른다 → 단방향 의존성 유지.

---

## 의존성

```
cache/
  PageCacheStore  ← CachePolicy, CacheEntry, WarmupStore 의존
  WarmupStore     ← 의존성 없음 (순수 Java I/O)
  CachePolicy     ← 의존성 없음
  CacheEntry      ← 의존성 없음

SpatialCacheEngine (api/) → PageCacheStore, WarmupStore (단방향)
PageCacheStore → SpatialRecordManager 없음 (순환 참조 없음) ✅
```

---

## 동작 흐름

```
[Spring SpatialCacheService]
  │
  └─ spatialCacheEngine.search(lat, lng, radius, batchLoader)
        ↓
      [SpatialCacheEngine — Phase 1: 분류]
      srm.searchRadiusCodesByPageId() → Map<pageId, codes>
      각 pageId:
        pageCacheStore.getOrMiss(pageId, codes)
          ├─ HIT  → hitResults 저장
          └─ MISS → pendingLoads.putIfAbsent(pageId, future)
                      ├─ winner (null 반환): double-check → toLoad 등록
                      └─ waiter (기존 future): waitFuture 등록
        ↓
      [SpatialCacheEngine — Phase 2: Batch Load]
      toLoad의 모든 codes flatten + distinct
      batchLoader.apply(codes) → DB 단 1회 조회 → Map<code, T>
      pageId별 결과 분배 → pageCacheStore.put() + future.complete()
        ↓
      [SpatialCacheEngine — Phase 3: 수집]
      HIT    → hitResults에서 직접
      winner → myFuture.getNow() (이미 완료)
      waiter → waitFuture.join() (winner 완료 대기)
        ↓
      [Spring SpatialCacheService]
      MBR 필터링 → 반환
```

---

## 핵심 설계 결정

### pageId 전체 저장

```
MISS 시 반경 내 codes만 저장하면:
  다른 좌표에서 같은 pageId HIT → 데이터 누락 발생

→ getAllCodesByPageId()로 해당 페이지 전체 codes 조회 후 저장
→ 이후 어떤 좌표에서 HIT해도 누락 없음
```

### MBR 필터링은 호출자 책임

```
pageId 전체 저장 → 반경 밖 데이터 포함 가능
→ PageCacheStore는 필터링 책임 없음
→ 호출자(SpatialCacheService)가 MBR 필터링 수행
→ 스토어는 저장/조회만 담당
```

### LinkedHashMap + synchronized

```
멀티스레드 환경 (Spring 요청 동시 처리)
→ LinkedHashMap은 thread-safe하지 않음
→ 모든 메서드 synchronized로 직렬화
→ access-order LRU로 핫스팟 pageId 보호
→ Thundering Herd 시 중복 데이터 덮어쓰기로 정합성 보장
```

---

## WarmupStore

재시작/rebuild 후 JVM 캐시가 비어있을 때, 과거 히트 히스토리 기반으로 Top N pageId를 미리 캐시에 올릴 수 있도록 지원합니다.

```java
long getHitCount(int pageId)          // pageId별 누적 접근 횟수
List<Integer> getTopPageIds(int n)    // 히트 횟수 내림차순 Top N
void recordAccess(int pageId)         // 접근 시 카운트 증가
void persist()                        // 히트 카운트 파일에 저장
```

**설계 원칙:**
```
pageId별 접근 카운트 → ConcurrentHashMap<Integer, AtomicLong>
파일 포맷: "pageId count" (한 줄에 하나)
재시작 시 생성자에서 자동 load() → 히스토리 복원
persist() 실패는 무시 → 엔진 동작에 영향 없음
rebuild 후 hitCounts 초기화 안 함 → 과거 히스토리가 워밍업 핵심
```

**워밍업 흐름 (Spring 담당):**
```
서버 시작 @PostConstruct
  → spatialCacheEngine.getWarmupCandidates(50)  // Top 50 pageId
  → hospitalRepo.findByPageId(pageId)            // DB 조회
  → spatialCacheEngine.putCache(pageId, data)    // JVM 캐시 적재

서버 종료 @PreDestroy
  → spatialCacheEngine.persistWarmup()           // 히트 카운트 저장
```

**Thread-safety:**
```
recordAccess → computeIfAbsent + AtomicLong.incrementAndGet() → 원자적
getTopPageIds → 스냅샷 정렬 → 읽기 전용 → 별도 동기화 불필요
persist → 순간 스냅샷 기록 → 정확한 순간 값 보장 불필요
```

---

## 제약사항

```
JVM 프로세스 종료 = 캐시 초기화
  → 재시작 시 WarmupStore.getTopPageIds()로 Top N pageId 선제 적재

동시성 안전:
  synchronized → 모든 메서드 직렬화
  clearCache() → pageCache.clear()로 내용물 제거 (final 참조 유지)
```

**Thundering Herd 방지:**
```
PageCacheStore 레벨: 동시 put → 덮어쓰기로 정합성 보장
SpatialCacheEngine 레벨: pendingLoads(ConcurrentHashMap<pageId, CompletableFuture>)
  → 동일 pageId MISS 시 winner 1개만 DB 조회
  → waiter는 winner CompletableFuture.join() 대기
  → putIfAbsent 직후 double-check로 race 방지 (Bug 10)
  → 상세: CONCURRENCY.md Bug 10
```
