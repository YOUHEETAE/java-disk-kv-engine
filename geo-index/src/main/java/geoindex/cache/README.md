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

### SpatialCache\<T\> (인터페이스, api/)

```java
public interface SpatialCache<T> {
    List<PageResult<T>> search(double lat, double lng, double radiusKm);
    void put(List<T> data);
}
```

Spring을 포함한 어떤 레이어에서도 이 인터페이스만 의존합니다.
`T`를 제네릭으로 열어두어 병원, 약국, 편의점 등 어떤 도메인에도 재사용 가능합니다.

---

### PageResult\<T\> (값 객체, api/)

```java
PageResult.hit(pageId, List<T> cached)   // 캐시 HIT
PageResult.miss(pageId, List<String> codes) // 캐시 MISS → DB 조회 필요 코드 목록
```

`search()` 결과를 pageId 단위로 HIT/MISS 분리해서 반환합니다.
호출자(Spring)가 MISS pageId만 추려서 DB를 한 번에 조회할 수 있습니다.

---

### SpatialCacheEngine\<T\> (구현체, cache/)

```java
public class SpatialCacheEngine<T> implements SpatialCache<T> {
    private final ConcurrentHashMap<Integer, List<T>> pageCache;
    private final SpatialRecordManager spatialRecordManager;
    private final SpatialIndex spatialIndex;
    private final Function<T, double[]> coordExtractor; // T → [lat, lng]
}
```

**search():**
```
spatialRecordManager.searchRadiusCodesByPageId() → Map<pageId, List<code>>
각 pageId → pageCache 확인
  HIT → PageResult.hit(pageId, cached)
  MISS → PageResult.miss(pageId, codes)
```

**put():**
```
List<T> → 각 item의 좌표 추출 (coordExtractor)
→ spatialIndex.toPageId(lat, lng)
→ pageCache.computeIfAbsent(pageId, ...).add(item)
```

`coordExtractor`를 생성자 주입으로 받아 도메인 객체의 좌표 추출 방식을 외부에서 결정합니다.

---

## 동작 흐름

```
[Spring SpatialCacheService]
  │
  ├─ spatialCache.search(lat, lng, radius)
  │     ↓
  │   [SpatialCacheEngine]
  │   MiniDB → pageId 목록 → ConcurrentHashMap 확인
  │     ├─ HIT  → PageResult.hit (데이터 즉시 반환)
  │     └─ MISS → PageResult.miss (codes 목록 반환)
  │
  ├─ MISS codes → spatialRecordManager.getAllCodesByPageId()
  │             → MariaDB IN 쿼리 한 번 (MISS pageId 전체)
  │
  ├─ spatialCache.put(dbResults)
  │     ↓
  │   coordExtractor → toPageId → pageCache 저장
  │
  └─ spatialCache.search() 재호출 → 전부 HIT
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
SpatialCacheEngine → SpatialRecordManager  (pageId 목록, 전체 codes 조회)
SpatialCacheEngine → SpatialIndex          (T → pageId 변환)
SpatialCacheService → SpatialCache<T>      (인터페이스만 의존, 엔진 모름)
```

Spring은 `SpatialCache<T>` 인터페이스만 알고, `SpatialCacheEngine` 구현체를 직접 참조하지 않습니다.

---

## 제약사항

```
JVM 프로세스 종료 = 캐시 초기화
  → 재시작 시 Warm-up 필요 (Lazy 방식으로 자연스럽게 채워짐)

동시성 안전:
  ConcurrentHashMap → 읽기 안전
  put() 시 computeIfAbsent → 같은 pageId 중복 삽입 방지

미구현:
  캐시 크기 제한 (Eviction 정책 없음)
  TTL (만료 시간 없음)
  → 병원 데이터는 주 1회 배치 업데이트 → 재빌드 시 JVM 재시작으로 초기화
```
