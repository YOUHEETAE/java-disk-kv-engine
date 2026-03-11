# API 모듈

공간 인덱스 기반 저장/검색 엔진 — 최상단 API 레이어

---

## 레이어 구조

```
Spring
  └─ SpatialCacheEngine       ← 최상단 (조율)
       ├─ SpatialRecordManager ← 파일 I/O 전담
       └─ PageCacheStore       ← 캐시 인프라 (cache/ 레이어)
```

Spring은 `SpatialCacheEngine` 하나만 의존합니다.
파일이냐 캐시냐는 엔진 내부 문제입니다.

---

## 클래스

### SpatialCacheEngine\<T\>

최상단 API. 파일 I/O(SpatialRecordManager)와 캐시(PageCacheStore)를 조율합니다.

```java
List<PageResult<T>> search(double lat, double lng, double radiusKm)
void putCache(int pageId, List<T> data)
void rebuild(Consumer<SpatialRecordManager> loader)
void clearCache()
long getCacheSize()
boolean isCached(int pageId)
CachePolicy getPolicy()
```

| 메서드 | 역할 |
|--------|------|
| `search` | pageId 조회 → HIT/MISS 판단 → PageResult 반환 |
| `putCache` | MISS 후 DB 결과를 JVM 캐시에 저장 |
| `rebuild` | 파일 재구축 + JVM 캐시 초기화 |
| `clearCache` | JVM 캐시 전체 초기화 |

**생성자:**
```java
new SpatialCacheEngine<>(srm)                  // CachePolicy.DEFAULT
new SpatialCacheEngine<>(srm, cachePolicy)     // 커스텀 정책
```

**의존성:**
```
SpatialCacheEngine → SpatialRecordManager  (파일 I/O 위임)
SpatialCacheEngine → PageCacheStore        (캐시 위임)
SpatialRecordManager → SpatialCacheEngine 없음 (순환 참조 없음) ✅
```

**`rebuild()`가 필요한 이유:**
```
단순 삭제 + 재생성:
  파일 교체 중 요청 → 빈 파일 읽음 ❌

rebuild() + atomic rename:
  임시 파일에 구축 → rename 전까지 기존 파일 서비스
  rename 완료 → JVM 캐시 초기화
  요청 중단 없음 ✅
```

Spring 사용 예:
```java
spatialCacheEngine.rebuild(srm ->
    hospitalRepo.findAllCodes().forEach(h ->
        srm.put(h.getLat(), h.getLng(), h.getCode().getBytes())
    )
);
```

---

### SpatialRecordManager

파일 I/O 전담. 캐시 관련 책임 없음.

```java
void put(double lat, double lng, byte[] value)
void rebuild(Consumer<SpatialRecordManager> loader)

Map<Integer, List<String>> searchRadiusCodesByPageId(double lat, double lng, double radiusKm)
List<String> getAllCodesByPageId(int pageId)

List<byte[]> searchRadius(double lat, double lng, double radiusKm)   // 벤치마크용
List<String> searchRadiusCodes(double lat, double lng, double radiusKm) // 벤치마크용
```

| 메서드 | 반환 | 용도 |
|--------|------|------|
| `put` | void | 파일에 병원 코드 저장 |
| `rebuild` | void | 임시 파일 구축 → atomic rename |
| `searchRadiusCodesByPageId` | `Map<pageId, List<String>>` | pageId 단위 codes 반환 |
| `getAllCodesByPageId` | `List<String>` | pageId 전체 codes 반환 (MISS 시 DB 조회용) |

**`searchRadiusCodesByPageId`가 필요한 이유:**
```
codes만 반환하면:
  어떤 pageId가 HIT인지 MISS인지 알 수 없음

Map<pageId, List<codes>> 반환:
  SpatialCacheEngine이 pageId 단위로 HIT/MISS 분기 가능
```

**`getAllCodesByPageId`가 필요한 이유:**
```
MISS 시 반경 내 codes만 DB 조회 후 캐시 저장하면:
  다른 좌표에서 같은 pageId HIT → 저장된 데이터가 일부뿐 → 누락 발생

→ pageId 전체 codes를 DB 조회 후 저장
→ overflow 체인까지 순회해서 해당 페이지의 모든 records 반환
```

**의존성:**
```
SpatialRecordManager → CacheManager  (버퍼 레이어)
SpatialRecordManager → SpatialIndex  (pageId 변환, 주입)
캐시 관련 의존성 없음 ✅
```

**핵심 설계: 두 티어 페이지 관리**
```java
private static final int PRIMARY_PAGES  = 32_768;  // Morton pageId 전용
private static final int OVERFLOW_PAGES = 40_960;  // overflow 전용 free list

PRIMARY_PAGES (0 ~ 32,767):
  Morton 코드 기반 pageId 사용
  공간 클러스터링 담당

OVERFLOW_PAGES (32,768 ~ 73,727):
  별도 Deque<Integer> free list로 관리
  Morton 기반 할당 절대 금지
  핫스팟 지역의 초과 레코드 수용
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

### RecordManager

Key-Value 기반 저장소. O(1) 직접 접근을 위한 인메모리 인덱스를 사용합니다.

```java
void put(String key, byte[] value)
byte[] get(String key)
```

---

### RecordId (값 객체)

레코드의 물리적 위치를 나타냅니다.

```java
class RecordId {
    int pageId;
    int slotId;  // O(1) 직접 접근
}
```

`equals` / `hashCode` 구현 → HashMap 키로 사용 가능
→ O(n) 슬롯 스캔에서 O(1) 직접 접근으로 개선

---

## 페이지 내부 구조

### 페이지 헤더 (16 bytes)

```
0-3:   recordCount
4-7:   freeSpaceStart
8-11:  magic (0xCAFEBABE)
12-15: overflowPageId
```

### 슬롯 디렉토리 (8 bytes/slot)

```
[offset (4)][length (4)]
```

### 레코드 형식

```
[valueLength (4)][value]
```

---

## 제약사항

**구현됨:**
- put / rebuild / search / putCache / clearCache
- searchRadiusCodesByPageId / getAllCodesByPageId
- overflow 체인 (readAllCodesFromChain)
- 두 티어 페이지 관리 (PRIMARY / OVERFLOW 분리)
- atomic rename 기반 무중단 rebuild

**미구현:**
- delete
- update
- 인덱스 영속화 (재시작 시 rebuild 필요)
- 동시성 제어 (ReadWriteLock)
- 체크섬 기반 파일 손상 감지
