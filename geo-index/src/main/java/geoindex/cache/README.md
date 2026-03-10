# Cache 모듈

pageId 단위 공간 캐시 엔진 — Spring 없이 순수 Java 제네릭으로 구현

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

### ~~SpatialCache\<T\> (인터페이스)~~ — Phase 10에서 제거

```
제거 이유:
  구현체가 SpatialCacheEngine 하나뿐 → 인터페이스 유지 비용만 증가
  Spring이 SpatialRecordManager만 알면 되는 구조로 변경
  → 인터페이스 추상화 불필요
```

---

### SpatialCacheEngine\<T\> (구현체, cache/)

```java
// Phase 9: SpatialRecordManager + SpatialIndex + coordExtractor 직접 참조
// Phase 10: 의존성 제거 → HIT/MISS 판단만 담당
public class SpatialCacheEngine<T> {
    private volatile ConcurrentHashMap<Integer, CacheEntry<T>> pageCache;
    private final CachePolicy policy;
}
```

> `coordExtractor` 제거 이유: SpatialCacheEngine이 SpatialRecordManager를 직접 참조하던
> 구조에서 역전됨. 좌표 추출 책임이 더 이상 캐시 엔진에 없음.

**getOrMiss(pageId, codes):**
```
pageCache.get(pageId)
  → null 또는 isExpired() → PageResult.miss(pageId, codes)
  → 유효 → PageResult.hit(pageId, cached)
```

**put(pageId, List\<T\>):**
```
CacheEntry.of(data) 또는 CacheEntry.of(data, expiresAt)
→ pageCache.put(pageId, entry)
→ maxSize 초과 시 evict
```

**clearCache():**
```
this.pageCache = new ConcurrentHashMap<>()  ← volatile write (atomic 교체)
기존 Map → 참조 끊김 → GC 대상
rebuild() 중 다른 스레드 → volatile read → 기존 Map 계속 읽음 → 중단 없음
```

`SpatialRecordManager`를 참조하지 않습니다. HIT/MISS 판단만 담당합니다.

---

## 동작 흐름

```
[Spring SpatialCacheService]
  │
  ├─ spatialRecordManager.search(lat, lng, radius)
  │     ↓
  │   [SpatialRecordManager]
  │   파일 → pageId + codes 조회
  │   cacheEngine.getOrMiss(pageId, codes)
  │     ├─ HIT  → PageResult.hit (JVM 캐시 데이터 즉시 반환)
  │     └─ MISS → PageResult.miss (codes 목록 반환)
  │
  ├─ MISS → MariaDB JOIN 조회
  │
  ├─ spatialRecordManager.putCache(pageId, dbResults)
  │     ↓
  │   cacheEngine.put(pageId, data) → JVM 캐시 저장
  │
  └─ spatialRecordManager.search() 재호출 → 전부 HIT
        ↓
      MBR 필터링 후 반환
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
→ SpatialCacheEngine은 필터링 책임 없음
→ 호출자(SpatialCacheService)가 MBR 필터링 수행
→ 엔진은 저장/조회만 담당
```

### ConcurrentHashMap

```
멀티스레드 환경 (Spring 요청 동시 처리)
→ HashMap 대신 ConcurrentHashMap
→ 락 없이 읽기, 세그먼트 락으로 쓰기
```

---

## 의존성

```
SpatialRecordManager → SpatialCacheEngine  (단방향)
SpatialCacheEngine   → SpatialRecordManager 없음 (순환 참조 없음) ✅
Spring               → SpatialRecordManager만 알면 됨
```

---

## 제약사항

```
JVM 프로세스 종료 = 캐시 초기화
  → 재시작 시 Warm-up 필요 (Lazy 방식으로 자연스럽게 채워짐)

동시성 안전:
  ConcurrentHashMap → 읽기 안전
  clearCache() volatile write → rebuild 중 기존 캐시 유지

미구현:
  ReadWriteLock 기반 rebuild 완전 동시성 보호
  Thundering Herd 방지 (동시 MISS → 중복 DB 조회)
```
