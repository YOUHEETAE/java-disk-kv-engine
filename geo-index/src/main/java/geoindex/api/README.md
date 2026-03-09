# API 모듈

공간 인덱스 기반 저장/검색 엔진 + 캐시 계층 인터페이스

---

## 클래스

### SpatialRecordManager.java

공간 인덱스를 통해 좌표 기반으로 레코드를 저장하고 반경 검색을 수행합니다.

**API:**
```java
void put(double lat, double lng, byte[] value)

List<byte[]> searchRadius(double lat, double lng, double radiusKm)
List<String> searchRadiusCodes(double lat, double lng, double radiusKm)

Map<Integer, List<String>> searchRadiusCodesByPageId(double lat, double lng, double radiusKm)
List<String> getAllCodesByPageId(int pageId)
```

| 메서드 | 반환 | 용도 |
|--------|------|------|
| `searchRadius` | `List<byte[]>` | 더미 데이터 벤치마크 |
| `searchRadiusCodes` | `List<String>` | 병원 코드 목록 |
| `searchRadiusCodesByPageId` | `Map<pageId, List<String>>` | 캐시 HIT/MISS 분기 |
| `getAllCodesByPageId` | `List<String>` | MISS pageId 전체 codes 조회 |

**`searchRadiusCodesByPageId`가 필요한 이유:**
```
기존 searchRadiusCodes():
  pageId 구분 없이 codes 전체 반환
  → 어떤 pageId가 HIT인지 MISS인지 알 수 없음

searchRadiusCodesByPageId():
  Map<pageId, List<codes>> 반환
  → SpatialCacheEngine이 pageId 단위로 HIT/MISS 분기 가능
```

**`getAllCodesByPageId`가 필요한 이유:**
```
MISS 시 반경 내 codes만 DB 조회 후 캐시 저장하면:
  다른 좌표에서 같은 pageId HIT → 저장된 데이터가 일부뿐 → 누락 발생

→ pageId 전체 codes를 DB 조회 후 저장
→ overflow 체인까지 순회해서 해당 페이지의 모든 records 반환
```

---

### SpatialCache\<T\> (인터페이스)

캐시 엔진의 추상화 계층입니다. Spring을 포함한 상위 레이어는 이 인터페이스만 의존합니다.

```java
public interface SpatialCache<T> {
    List<PageResult<T>> search(double lat, double lng, double radiusKm);
    void put(List<T> data);
}
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

### 문제: 단일 페이지 풀의 한계

초기 구현에서는 PRIMARY 페이지와 OVERFLOW 페이지가 같은 풀을 공유했습니다.

```
문제:
  서울 강남처럼 병원 밀집 지역 → 특정 pageId에 레코드 집중
  → overflow 발생 → 전체 풀에서 새 페이지 할당
  → 할당된 overflow 페이지가 Morton 기반 pageId와 충돌
  → "no available pages" 오류 또는 무한 재귀
```

### 해결: PRIMARY / OVERFLOW 분리

```java
private static final int PRIMARY_PAGES  = 32_768;  // Morton pageId 전용
private static final int OVERFLOW_PAGES = 40_960;  // overflow 전용 free list
private static final int TOTAL_PAGES    = PRIMARY_PAGES + OVERFLOW_PAGES;
```

```
PRIMARY_PAGES (0 ~ 32,767):
  Morton 코드 기반 pageId 사용
  공간 클러스터링 담당

OVERFLOW_PAGES (32,768 ~ 73,727):
  별도 Deque<Integer> free list로 관리
  Morton 기반 할당 절대 금지
  핫스팟 지역의 초과 레코드 수용
```

**overflow 할당 원칙:**
```
overflow 페이지는 절대 Morton 기반으로 할당하지 않는다
→ Morton 공간과 분리해야 공간 클러스터링 속성이 보존됨
→ free list에서만 꺼내 사용
```

---

## 연산

### put(lat, lng, value)

```
1. spatialIndex.toPageId(lat, lng)  → Morton 기반 pageId
2. cacheManager.getPage(pageId)     → 페이지 로드
3. PageLayout.isInitialized()?      → 미초기화 시 initializePage()
4. writeWithOverflow(page, value)   → 슬롯에 기록
5. cacheManager.putPage(page)       → dirty 마킹
```

### writeWithOverflow(page, value)

```
PageLayout.writeRecord(page, value)
  └─ 성공 (slotId >= 0): 완료
  └─ 실패 (slotId == -1, 공간 부족):
       getOverflowPageId(page)
         └─ NO_OVERFLOW: overflowFreeList.pop() → setOverflowPageId()
         └─ 기존 overflow 있음: 해당 페이지로 이동
       writeWithOverflow(overflowPage, value)  ← 재귀
```

### getAllCodesByPageId(pageId)

```
1. cacheManager.getPage(pageId)       → 기본 페이지 로드
2. PageLayout.readAllRecords(page)    → 레코드 수집
3. overflow 체인 순회
   while overflowPageId != NO_OVERFLOW:
     overflowPage → readAllRecords → 수집
4. 전체 codes 반환
```

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

## 의존성

```
SpatialRecordManager → CacheManager
SpatialRecordManager → SpatialIndex (주입)
SpatialRecordManager → PageLayout
RecordManager        → CacheManager
RecordManager        → PageLayout
SpatialCacheEngine   → SpatialRecordManager  (searchRadiusCodesByPageId, getAllCodesByPageId)
SpatialCacheEngine   → SpatialIndex          (toPageId)
```

---

## 제약사항

**구현됨:**
- put / searchRadius / searchRadiusCodes
- searchRadiusCodesByPageId / getAllCodesByPageId
- Overflow 체인
- 두 티어 페이지 관리 (PRIMARY / OVERFLOW 분리)
- SpatialCache\<T\> / PageResult\<T\> 캐시 인터페이스

**미구현:**
- delete
- update
- 인덱스 영속화 (재시작 시 재빌드 필요)
