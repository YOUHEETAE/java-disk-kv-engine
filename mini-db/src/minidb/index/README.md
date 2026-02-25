# Index 모듈

공간 인덱스 구현 - GeoHash / 힐버트 커브 기반 PageId 클러스터링

---

## 목표

공간적으로 가까운 병원을 같은/인접 페이지에 클러스터링하여 반경 검색 I/O 최소화

```
Full Scan:  79,081건 전체 스캔
GeoHash:    1,379건 후보 (169개 셀)
힐버트:     2,102건 후보 (268개 PageId)
```

---

## 인터페이스

### SpatialIndex.java

```java
public interface SpatialIndex {
    int toPageId(double lat, double lng);          // 삽입 시
    List<Integer> getPageIds(double lat, double lng, double radiusKm);  // 검색 시
}
```

원형 필터링은 이 엔진의 책임이 아닙니다. 애플리케이션이 담당합니다.

---

## GeoHash

### 변환 흐름

```
(lat, lng) → Base32 문자열 ("wydm9x") → long → % MAX_PAGE → PageId
```

### 핵심: hashCode() 대신 Base32 → long 변환

```
hashCode() 사용 시:  공간 근접성 파괴 (랜덤 분산)
Base32 → long 변환:  접두사가 같으면 long값도 가까움 → 공간 클러스터링
```

### getPageIds: neighbor() 방식

```
steps = ceil(radiusKm / 1.2) + 1  → 6
범위: -6 ~ +6 = 13 × 13 = 169개 셀
각 셀 → % MAX_PAGE → PageId
```

| 상수 | 값 | 의미 |
|------|-----|------|
| PRECISION | 6 | 격자 크기 1.2km |
| MAX_PAGE | 10,000 | 최대 페이지 수 |
| CELL_SIZE_KM | 1.2 | precision 6 격자 크기 |

---

## 힐버트 커브

### 변환 흐름

```
(lat, lng) → (x, y) 정수 격자 → 힐버트값 (long) → / RANGE_PER_PAGE → PageId
```

### 핵심: % 대신 / RANGE_PER_PAGE

GeoHash는 `neighbor()` 로 셀을 직접 지정하기 때문에 `%` 방식이 문제없습니다.
힐버트는 `min ~ max` 선형 범위로 PageId를 구하기 때문에 `%` 는 연속성을 파괴합니다.

```
% MAX_PAGE    → 힐버트값을 10,000으로 접어버림 → PageId 점프
/ RANGE_PER_PAGE → 힐버트값을 선형 분할 → 연속성 보존

PageId = hilbertValue / (MAX_HILBERT / MAX_PAGE)
```

### getPageIds: 선형 범위 방식

```
centerH = encode(lat, lng)
delta = steps * steps * 200  (실측 기반)

minPageId = (centerH - delta) / RANGE_PER_PAGE
maxPageId = (centerH + delta) / RANGE_PER_PAGE
→ minPageId ~ maxPageId 순회 (약 268개)
```

| 상수 | 값 | 의미 |
|------|-----|------|
| ORDER | 15 | 격자: 32768 × 32768 |
| MAX_HILBERT | 2^30 ≈ 10억 | 힐버트값 전체 범위 |
| RANGE_PER_PAGE | 107,374 | 페이지당 힐버트값 범위 |
| KM_PER_GRID | 0.0187km | 1격자 크기 |

### 힐버트 경계 점프 문제

힐버트 커브는 큰 사분면 경계에서 힐버트값이 크게 점프합니다.

```
강남 기준 실측:
  북쪽 5km: 힐버트값 차이       63,621  (같은 사분면)
  남쪽 5km: 힐버트값 차이   11,704,774  (사분면 경계 통과)
  최대 차이:                12,490,934
```

이 때문에 delta를 `steps * steps * 200` 으로 잡아야 27건 완전 일치합니다.
상세 분석은 [HILBERT_IMPLEMENTATION.md](./HILBERT_IMPLEMENTATION.md) 참고.

---

## GeoHash vs 힐버트 비교

| 항목 | GeoHash | 힐버트 |
|------|---------|--------|
| **변환** | 좌표 → 문자열 → long | 좌표 → long (직접) |
| **PageId 방식** | % MAX_PAGE | / RANGE_PER_PAGE |
| **getPageIds** | neighbor() 셀 직접 지정 | 선형 범위 순회 |
| **후보 수** | 1,379건 | 2,102건 |
| **경계 문제** | neighbor()로 우회 | delta로 보정 |
| **동적 delta** | 불필요 | 필요 (현재 하드코딩) |

---

## 파일 구조

```
index/
  SpatialIndex.java          인터페이스
  GeoHash.java               순수 계산 (좌표 → geohash → long)
  GeoHashIndex.java          SpatialIndex 구현체
  HilbertCurve.java          순수 계산 (좌표 → 힐버트값)
  HilbertIndex.java          SpatialIndex 구현체
  HILBERT_IMPLEMENTATION.md  힐버트 구현 문제 해결 과정
```

---

## 의존 관계

```
GeoHashIndex  → GeoHash
HilbertIndex  → HilbertCurve
둘 다         → SpatialIndex 구현
SpatialRecordManager → SpatialIndex 주입
```

---

## 향후 개선

```
힐버트 동적 delta:
  좌표마다 경계 점프 크기가 다름
  현재는 강남 기준 실측값 하드코딩
  → 검색 시점에 8방향 실측으로 delta 계산

후보 수 최적화:
  격자 좌표(x, y) 직접 순회 방식
  → GeoHash 수준(1,379건)으로 감소 가능
```
