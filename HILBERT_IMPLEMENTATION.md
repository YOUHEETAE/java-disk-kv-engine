# 힐버트 커브 인덱스 구현 - 문제 해결 과정

> **% MAX_PAGE 방식 실패 → / RANGE_PER_PAGE 전환 → Multi-Interval Query 도달**
> 실제 Hilbert R-Tree가 왜 필요한지 직접 경험한 기록

---

## 목차

1. [배경](#-배경)
2. [1차 시도: % MAX_PAGE → Full Scan 退化](#-1차-시도--maxpage--full-scan-退化)
3. [원인 분석: 힐버트 경계 점프](#-원인-분석-힐버트-경계-점프)
4. [2차 시도: / RANGE_PER_PAGE + delta 보정](#-2차-시도--range_per_page--delta-보정)
5. [3차 시도: 격자 순회 방식](#-3차-시도-격자-순회-방식)
6. [격자 순회의 한계: 671만 연산 병목](#-격자-순회의-한계-671만-연산-병목)
7. [4차 시도: Multi-Interval Query](#-4차-시도-multi-interval-query)
8. [최종 결과 및 분석](#-최종-결과-및-분석)
9. [남은 과제](#-남은-과제)

---

## 🎯 배경

GeoHash와 동일한 `SpatialIndex` 인터페이스로 힐버트 커브를 구현했습니다.

```java
public interface SpatialIndex {
    int toPageId(double lat, double lng);
    List<Integer> getPageIds(double lat, double lng, double radiusKm);
}
```

힐버트값은 연속적이라 `min ~ max` 범위로 PageId를 구할 수 있다고 판단했습니다.

---

## ❌ 1차 시도: % MAX_PAGE → Full Scan 退化

```java
public int toPageId(double lat, double lng) {
    long h = HilbertCurve.encode(lat, lng);
    return (int)(h % MAX_PAGE);
}
```

### 결과

```
후보 수: 79,081건  ← Full Scan과 동일
결과:    0건
```

### 원인

```
delta = steps * steps = 268 * 268 = 71,824
71,824 % 10,000 → 0 ~ 9,999 전부 커버
→ 모든 PageId 조회 → Full Scan 退化
```

---

## 🔍 원인 분석: 힐버트 경계 점프

힐버트 커브는 재귀적으로 사분면을 회전/반전해서 붙입니다.
큰 사분면 경계를 넘을 때 힐버트값이 크게 점프합니다.

### 강남 좌표 기준 8방향 실측

```
중심:     (37.4979, 127.0276) → 힐버트값 416,884,576

북쪽 5km: 차이       63,621  ← 같은 사분면, 연속
남쪽 5km: 차이   11,704,774  ← 사분면 경계 통과, 거대 점프
서쪽 5km: 차이      263,494
동쪽 5km: 차이      786,170
대각 최대: 차이   12,490,934
```

---

## ✅ 2차 시도: / RANGE_PER_PAGE + delta 보정

### % vs / 비교

```
% MAX_PAGE      → 힐버트값을 10,000으로 접어버림 → 연속성 파괴
/ RANGE_PER_PAGE → 힐버트값을 선형 분할 → 연속성 보존
```

### delta 보정 과정

| delta | 후보 수 | 결과 | 비고 |
|-------|--------|------|------|
| `steps * 2` | 8,393건 | 0건 | 부족 |
| `steps * steps * 2` | 22건 | 19건 | 8건 누락 |
| `steps * steps * 200` | 2,102건 | 27건 ✅ | 실측 최대값 커버 |

```
실측 최대 delta:     12,490,934
steps * steps * 200: 14,364,800  ← 커버
```

### 한계

```
delta 하드코딩 → 강남 기준, 다른 좌표에서 누락 가능
PageId 275개 → 엉뚱한 지역까지 포함
```

---

## 🔬 3차 시도: 격자 순회 방식

delta 하드코딩 문제를 해결하기 위해 격자 좌표를 직접 순회하는 방식을 시도했습니다.

### 발견: 위도/경도 격자 크기가 다르다

```
위도 1격자: 0.0185km  (5.5도 × 110km / 32768)
경도 1격자: 0.0095km  (3.5도 × 88.9km / 32768)
→ stepsY, stepsX 분리 필요
```

### 행별 dx 동적 계산으로 원형 근사

```java
for (long dy = -stepsY; dy <= stepsY; dy++) {
    double remainKm = sqrt(radius² - (dy * KM_LAT)²);
    long maxDx = ceil(remainKm / KM_LNG);
    for (long dx = -maxDx; dx <= maxDx; dx++) {
        // 각 격자 → encodeGrid(x, y) → PageId
    }
}
```

### 결과

```
후보 수: 97건   ← GeoHash 1,379건 대비 14배 적음 ✅
결과:    27건   ✅
검색:    37ms   ← 문제
```

---

## ⚠️ 격자 순회의 한계: 671만 연산 병목

```
GeoHash neighbor():  169번 연산
힐버트 격자 순회:    447,975번 × xy2d 15번 = 6,719,625번 연산
                     → GeoHash 대비 39,760배
```

### 왜 GeoHash는 적은가

```
GeoHash: neighbor()로 인접 셀 직접 지정 → 169번
힐버트:  반경 안 모든 격자 순회 필수 → 447,975번
         GeoHash처럼 "인접 셀 직접 지정" 불가 → 구조적 한계
```

---

## ✅ 4차 시도: Multi-Interval Query + 사각형 MBR

### 핵심 아이디어

원형 영역은 힐버트 곡선 위에서 **하나의 연속 구간이 아닙니다.**

```
격자 (x,y) → 힐버트값 나열 → 정렬

h: ... 3766 ... 3772 3773 ... 3775 ... 3879 3880 3881 3882 3883 3884 ... 3889 3890 ...
       ↓ Interval Merge
       [3766], [3772~3773], [3775], [3879~3884], [3889~3890]
       → 5개 disjoint interval → PageId 12개만 읽기
```

### 구현

```java
// 사각형 MBR 격자 순회 → visited[PageId] 마킹
// 원형 필터링은 프론트엔드 위임
boolean[] visited = new boolean[MAX_PAGE]; // 10KB, sort 불필요

for (long dy = -stepsY; dy <= stepsY; dy++) {
    for (long dx = -stepsX; dx <= stepsX; dx++) { // 고정 범위 (원형 조건 제거)
        long h = HilbertCurve.encodeGrid(x, y);
        visited[(int)(h / RANGE_PER_PAGE)] = true;
    }
}

// visited 배열 순회 → 연속 구간 추출 (이미 정렬됨)
```

### 결과

```
후보 수:     103건  ✅ (사각형 MBR, 원형 필터링은 프론트엔드 위임)
PageId 수:   12개   ✅ (선형 275개, GeoHash 169개 대비)
interval 수: 5개
검색:        33~37ms
결과:        27건   ✅
```

---

## 📊 최종 결과 및 분석

### 방식별 비교

| 방식 | PageId 수 | interval | 후보 수 | 검색 시간 |
|------|----------|----------|--------|---------|
| 선형 범위 | 275 | 1 | 2,102건 | 0~16ms |
| 격자 순회 | 97 | - | 97건 | 37ms |
| **Multi-Interval (MBR)** | **12** | **5** | **103건** | **33~37ms** |
| GeoHash | 169 | 분산 | 1,379건 | 0~16ms |

### Multi-Interval이 확정된 이유

```
힐버트의 강점 = 공간 연속성
공간 연속성의 증거 = PageId 12개 / 5개 interval

선형 범위로는 이걸 보여줄 수 없음
→ delta로 억지로 구간을 늘린 것 → 힐버트 강점이 아니라 한계를 보여주는 방식

Multi-Interval이 있어야
"원형이 힐버트 곡선 위에서 5개 구간으로 표현된다"
"그 5개 구간이 PageId 12개만 커버한다"
→ 이게 힐버트의 진짜 강점
```

### 현재 구현의 한계

```
getPageIds() 계산 비용: 37ms (격자 순회 671만 연산)
실제 I/O:               거의 0ms (PageId 12개)

대용량 HDD 환경에서는:
  getPageIds() 계산: 37ms (고정)
  GeoHash I/O:      169페이지 × HDD 랜덤 10ms = 1,690ms
  Hilbert I/O:       12페이지 × HDD 순차  1ms =    12ms
  → Hilbert 압도적 우위
```

---

## 🔮 남은 과제

### 격자 순회 없는 Interval 직접 계산

```
현재: 모든 격자(x,y) → 힐버트값 계산 → 671만 연산
개선: 힐버트 곡선을 따라 interval 경계만 직접 계산
      → 격자 순회 없이 동일한 interval 추출

이것이 실제 Hilbert R-Tree가 하는 방식
구현 복잡도가 급격히 올라감 → 현재 구현의 한계
```

### 동적 delta (선형 범위 방식 개선 시)

```java
// 현재: 강남 기준 하드코딩
long delta = steps * steps * 200;

// 개선 방향:
// 중심 → 8방향 경계 좌표 힐버트값 실측 → max delta 사용
```

---

## 📚 참고

- [Index 모듈 README](./README.md)
- [벤치마크 결과](../benchmark/README.md)
- [루트 README](../../../README.md)
