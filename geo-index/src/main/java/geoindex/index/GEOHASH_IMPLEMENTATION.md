# GeoHash 인덱스 구현 - 문제 해결 과정

> **고정 셀 누락 → % 연산 지역성 파괴 → Morton SHIFT 뭉침 → Morton 직접 pageId 도달**
> 한국 좌표 특성과 sparse 매핑 테이블의 필요성을 직접 경험한 기록

---

## 목차

1. [배경](#-배경)
2. [1차 시도: steps × steps 고정 셀 → 반경 경계 누락](#-1차-시도-steps--steps-고정-셀--반경-경계-누락)
3. [2차 시도: 동적 steps + % MAX_PAGES → 공간 지역성 파괴](#-2차-시도-동적-steps--maxpages--공간-지역성-파괴)
4. [3차 시도: Morton SHIFT → 한국 좌표에서 pageId 뭉침](#-3차-시도-morton-shift--한국-좌표에서-pageid-뭉침)
5. [4차 시도: Morton 직접 pageId + sparse 매핑 테이블](#-4차-시도-morton-직접-pageid--sparse-매핑-테이블)
6. [5차: getPageIds() 경계값 오버플로우 수정](#-5차-getpageids-경계값-오버플로우-수정)
7. [최종 결과 및 분석](#-최종-결과-및-분석)

---

## 🎯 배경

GeoHash는 위도/경도 비트를 번갈아 인터리빙하는 Morton 코드 기반 공간 인덱스입니다.

```
lng: 101  lat: 110
→ 인터리빙: 110110 (lng 홀수 bit, lat 짝수 bit)
→ 가까운 좌표 = 가까운 Morton 값 (Z-curve 공간 근접성 보존)
```

목표는 `SpatialIndex` 인터페이스 구현입니다.

```java
public interface SpatialIndex {
    int toPageId(double lat, double lng);                              // 삽입 시
    List<Integer> getPageIds(double lat, double lng, double radiusKm); // 검색 시
}
```

---

## ❌ 1차 시도: steps × steps 고정 셀 → 반경 경계 누락

```java
private static final int PRECISION = 6;  // 격자 1.2km
private static final int STEPS = 3;      // 중심 포함 3×3 고정

public List<Integer> getPageIds(double lat, double lng, double radiusKm) {
    // 중심 셀에서 STEPS 방향으로 neighbor() 탐색
    for (int dx = -STEPS; dx <= STEPS; dx++) {
        for (int dy = -STEPS; dy <= STEPS; dy++) { ... }
    }
}
```

### 결과

```
반경 5km 요청 시:
  3×3 셀 = 3.6km만 커버 (1.2km × 3)

후보: 70건
실제 반경 내 병원: 27건
누락: 23건 ❌
```

### 원인

```
STEPS = 3 → 커버 반경 = 1.2km × 3 = 3.6km
반경 5km 요청 → 경계 1.4km 구간 누락
```

---

## ❌ 2차 시도: 동적 steps + % MAX_PAGES → 공간 지역성 파괴

누락 문제를 해결하기 위해 steps를 반경에 맞게 동적으로 계산했습니다.

```java
int steps = (int) Math.ceil(radiusKm / 1.2) + 1;  // 5km → steps = 6
// 6×2+1 = 13 → 13×13 = 169개 셀 탐색
```

### pageId 매핑 방식의 문제

```java
public int toPageId(double lat, double lng) {
    String geohash = GeoHash.encode(lat, lng, PRECISION);
    long code = toLong(geohash);
    return (int)(code % MAX_PAGES);  // MAX_PAGES = 10,000
}
```

### 결과

```
후보: 1,379건  ← 기대: 27건 근처
결과: 27건 ✅  (정답은 나오지만)
```

### 원인 분석

```
% MAX_PAGES 연산이 공간 지역성을 파괴함

인접한 두 좌표:
  (37.4979, 127.0276) → code: 4,521,389,104 % 10,000 = 9,104
  (37.4980, 127.0277) → code: 4,521,389,201 % 10,000 = 9,201

Morton 값이 인접해도 % 연산 결과는 전혀 다를 수 있음
→ 같은 지역 좌표가 pageId 전역에 흩어짐
→ 검색 시 169개 셀이 서로 다른 10,000개 pageId로 매핑
→ 후보가 1,379건으로 폭발
```

---

## ❌ 3차 시도: Morton SHIFT → 한국 좌표에서 pageId 뭉침

% 연산의 지역성 파괴를 해결하기 위해 Morton 코드 상위 비트를 pageId로 사용했습니다.

```java
public int toPageId(double lat, double lng) {
    long morton = GeoHash.toMorton(lat, lng, PRECISION);
    return (int)(morton >> SHIFT);
}
```

### SHIFT 값 탐색

| SHIFT | 최대 pageId | 예상 파일 크기 | 문제 |
|-------|------------|--------------|------|
| 10 | 948,627 | 3,705MB | 파일 너무 큼 |
| 15 | 29,644 | 115MB | 아직 큼 |
| 20 | 926 | 4MB | pageId **1~2개**로 뭉침 |

### 원인: 한국 좌표의 Morton 공간 위치

```
전체 Morton 공간: 0 ~ 2^30 (약 10억)
한국 좌표 범위:   Morton 공간의 97% 지점에 집중

반경 5km = Morton 공간에서 약 77 차이
→ SHIFT=20: 77 >> 20 = 0 → 전부 pageId 0 또는 1로 뭉침
→ SHIFT=10: 유의미한 분산이지만 pageId 범위가 너무 넓음

어떤 SHIFT 값으로도 "충분한 분산 + 적절한 범위" 동시 달성 불가
```

---

## ✅ 4차 시도: Morton 직접 pageId + sparse 매핑 테이블

### 핵심 아이디어

Morton 값 자체를 pageId로 쓰되, 파일 크기 문제는 DiskManager의 sparse 매핑 테이블로 해결합니다.

```
Morton 값 → pageId (직접 사용)
DiskManager: HashMap<pageId, 파일오프셋>
→ pageId가 6천만이어도 실제 파일 = 데이터 페이지 수 × 4KB
→ 파일 크기는 삽입한 pageId 수에만 비례
```

### toPageId

```java
public int toPageId(double lat, double lng) {
    return (int) GeoHash.toMorton(lat, lng, PRECISION);
}
```

### getPageIds - 격자 순회 방식

% 연산을 완전히 제거하고 latBits/lngBits 범위를 직접 순회합니다.

```java
public List<Integer> getPageIds(double lat, double lng, double radiusKm) {
    // 1. MBR(최소 경계 직사각형) 계산
    double deltaDegreeY = radiusKm / 110.0;
    double deltaDegreeX = radiusKm / (111.32 * Math.cos(Math.toRadians(lat)));

    // 2. 네 꼭짓점 좌표 → 비트 변환
    long minLatBits = latToBits(lat - deltaDegreeY, PRECISION);
    long maxLatBits = latToBits(lat + deltaDegreeY, PRECISION);
    long minLngBits = lngToBits(lng - deltaDegreeX, PRECISION);
    long maxLngBits = lngToBits(lng + deltaDegreeX, PRECISION);

    // 3. 비트 범위 격자 순회 → Morton 재조합 → pageId 수집
    for (long latBits = minLatBits; latBits <= maxLatBits; latBits++) {
        for (long lngBits = minLngBits; lngBits <= maxLngBits; lngBits++) {
            long morton = GeoHash.interleave(lngBits, latBits);
            pageSet.add((int) morton);
        }
    }
}
```

### 결과

```
반경 5km, 강남 기준:
  pageId 수: 187개  (이전 1~2개 → 187개) ✅
  후보 수:   1,366건
  결과:      27건 ✅
  검색:      < 1ms
```

---

## 🔧 5차: getPageIds() 경계값 오버플로우 수정

4차 구현에서 maxLatBits/maxLngBits 계산 시 유효 범위를 초과하는 버그가 발견됐습니다.

### 문제

```java
// latToBits()의 최대 반환값: 32767 (2^15 - 1)
long maxLatBits = latToBits(maxLat, PRECISION) + 1;  // → 32768 가능
long maxLngBits = lngToBits(maxLng, PRECISION) + 1;  // → 32768 가능

// interleave(32768, ...) → 유효 범위 초과 → 잘못된 Morton 코드 생성
// → 경계 근처 pageId 누락
```

이 버그는 단일 스레드에서도 재현됩니다. 극좌표(위도 ±90°, 경도 ±180°) 근처 좌표를 검색할 때 경계 셀의 페이지가 누락됩니다.

### 수정

```java
// Before
long maxLatBits = latToBits(maxLat, PRECISION) + 1;
long maxLngBits = lngToBits(maxLng, PRECISION) + 1;

// After — 상한 클램핑
long maxLatBits = Math.min((1L << 15) - 1, latToBits(maxLat, PRECISION) + 1);
long maxLngBits = Math.min((1L << 15) - 1, lngToBits(maxLng, PRECISION) + 1);
```

---

## 📊 최종 결과 및 분석

### 설계 개선 이력

| 차수 | 방식 | pageId 수 | 후보 수 | 결과 | 문제 |
|------|------|----------|--------|------|------|
| 1차 | 고정 셀 (3×3) | 9개 | 70건 | 누락 23건 ❌ | 반경 경계 누락 |
| 2차 | 동적 steps + % MAX | 169개 | 1,379건 | 27건 ✅ | 공간 지역성 파괴 |
| 3차 | Morton SHIFT=20 | 1~2개 | 집중 | - ❌ | 한국 좌표 뭉침 |
| **4차** | Morton 직접 + sparse | **187개** | **1,366건** | **27건 ✅** | - |
| **5차** | + 경계값 클램핑 | 187개 | 1,366건 | 27건 ✅ | - |

### sparse 매핑 테이블이 핵심인 이유

```
Morton 직접 pageId를 쓰려면 pageId가 6천만까지 올라감
→ 연속 파일로 구성하면: 6천만 × 4KB = 240TB

DiskManager sparse 매핑:
  HashMap<pageId, 파일오프셋>으로 실제 데이터만 저장
  실제 파일 크기: 삽입된 pageId 수 × 4KB
  한국 병원 79,081건 기준: ~750개 페이지 × 4KB = 약 3MB
```

### GeoHash vs Hilbert 비교

| 항목 | GeoHash | Hilbert |
|------|---------|---------|
| **pageId 수** | 187개 | 13개 |
| **후보 수** | 1,366건 | 103건 |
| **getPageIds 연산** | 187번 | 671만 번 |
| **검색 시간** | < 1ms | 33~37ms |
| **Seek Count** | 720 | 124 |

```
SSD 환경:  GeoHash 유리 (< 1ms vs 33ms, I/O 비용 낮음)
HDD 환경:  Hilbert 유리 (pageId 13개 × 순차 I/O)
현재 서비스: SSD → GeoHash 채택
```

---

## 📚 참고

- [Index 모듈 README](./README.md)
- [Hilbert 구현 과정](../../../../../HILBERT_IMPLEMENTATION.md)
- [루트 README](../../../../../README.md)
