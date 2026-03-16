# Cache 모듈

pageId 단위 JVM 캐시 인프라 — Spring 없이 순수 Java 제네릭으로 구현

---

## 왜 엔진 레벨에 캐시가 필요한가

```
기존 Redis Geohash 방식:
  요청 → Redis MGET(9개 격자) → 네트워크 왕복
  MISS → MariaDB 조회 → Redis 저장 → 재반환

이 모듈:
  요청 → ConcurrentHashMap.get(pageId) → 나노초
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

pageId 단위 ConcurrentHashMap 캐시 인프라.
TTL 만료 체크, maxSize 초과 시 evict, clearCache를 담당합니다.

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
pageCache.get(pageId)
  → null 또는 isExpired() → PageResult.miss(pageId, codes)
  → 유효 → PageResult.hit(pageId, cached)
```

**put(pageId, List\<T\>):**
```
pageCache.put(pageId, CacheEntry.of(data))
  → ConcurrentHashMap.put() → atomic 덮어쓰기
  → maxSize 초과 시 evictOne()

덮어쓰기를 선택한 이유:
  같은 pageId = 같은 DB 조회 = 같은 데이터
  Thundering Herd 시 동일 pageId 동시 put
  → addAll이면 중복 데이터 쌓임 ❌
  → 덮어쓰기면 결과 동일, 중복 없음 ✅
```

**clearCache():**
```
this.pageCache = new ConcurrentHashMap<>()  ← volatile write (atomic 교체)
기존 Map → 참조 끊김 → GC 대상
rebuild() 중 다른 스레드 → volatile read → 기존 Map 계속 읽음 → 중단 없음
```

---

## Thread-safety

`put()`은 `synchronized`로 보호됨. maxSize 체크와 실제 put 사이의 race condition으로 maxSize 초과 방지.
→ [CONCURRENCY.md Bug 9 참고](../../../../../CONCURRENCY.md)

---

## 의존성

```
cache/
  PageCacheStore  ← CachePolicy, CacheEntry 의존
  CachePolicy     ← 의존성 없음
  CacheEntry      ← 의존성 없음

SpatialCacheEngine (api/) → PageCacheStore (단방향)
PageCacheStore → SpatialRecordManager 없음 (순환 참조 없음) ✅
```

---

## 동작 흐름

```
[Spring SpatialCacheService]
  │
  └─ spatialCacheEngine.search(lat, lng, radius)
        ↓
      [SpatialCacheEngine]
      srm.searchRadiusCodesByPageId() → Map<pageId, codes>
      pageCacheStore.getOrMiss(pageId, codes)
        ├─ HIT  → PageResult.hit (JVM 캐시 데이터 즉시 반환)
        └─ MISS → PageResult.miss (codes 목록 반환)
        ↓
      [Spring SpatialCacheService]
      MISS → MariaDB JOIN 조회
      spatialCacheEngine.putCache(pageId, dbResults)
        ↓
      pageCacheStore.put(pageId, data) → JVM 캐시 저장 (덮어쓰기)
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

### ConcurrentHashMap + 덮어쓰기

```
멀티스레드 환경 (Spring 요청 동시 처리)
→ ConcurrentHashMap.put() → 세그먼트 락으로 atomic 덮어쓰기 보장
→ 읽기는 락 없이 동시 실행 가능
→ Thundering Herd 시 중복 데이터 쌓임 방지
```

---

## 제약사항

```
JVM 프로세스 종료 = 캐시 초기화
  → 재시작 시 Warm-up 필요 (Lazy 방식으로 자연스럽게 채워짐)

동시성 안전:
  ConcurrentHashMap → put/get 동시 실행 안전
  clearCache() volatile write → rebuild 중 기존 캐시 유지

미구현:
  Thundering Herd 완전 방지 (동시 MISS → 중복 DB 조회 허용)
  → 같은 데이터 덮어쓰기로 정합성은 보장
  → computeIfAbsent + Future 조합으로 개선 가능하나 복잡도 대비 효과 낮음
```
