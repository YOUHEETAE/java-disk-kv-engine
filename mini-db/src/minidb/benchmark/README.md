# Benchmark 모듈

공간 인덱스 성능 측정 - Full Scan vs GeoHash vs Hilbert 3방향 비교

---

## 테스트 환경

| 항목 | 내용 |
|------|------|
| **데이터 규모** | 79,081건 (실제 병원 데이터 규모) |
| **검색 조건** | 강남 좌표 기준 반경 5km |
| **검색 좌표** | lat: 37.4979, lng: 127.0276 |
| **페이지 크기** | 4KB |
| **캐시 전략** | Write-Back (검색 전 캐시 초기화) |
| **랜덤 시드** | 42 (동일한 더미 데이터 보장) |
| **필터링** | 사각형 MBR (원형 필터링은 프론트엔드 위임) |

---

## 최종 벤치마크 결과

| 건수 | Full Scan | GeoHash | Hilbert |
|------|----------|---------|---------|
| 10,000 | 100ms | 0ms | 29ms |
| 20,000 | 133ms | 0ms | 33ms |
| 30,000 | 259ms | 2ms | 32ms |
| 50,000 | 292ms | 0ms | 33ms |
| 79,081 | 434ms | 0ms | 33ms |
| 100,000 | 528ms | 0ms | 47ms |
| 200,000 | 660ms | 3ms | 24ms |
| 500,000 | 768ms | 0ms | 24ms |
| 1,000,000 | 1,177ms | 6ms | 34ms |

---

## PageId 탐색 수 비교 (반경 5km, 강남 기준)

| 방식 | PageId 수 | interval 수 | 후보 수 |
|------|----------|------------|--------|
| Full Scan | 10,000 | 1 | 79,081건 |
| 선형 범위 | 275 | 1 | 2,102건 |
| GeoHash | 169 | 분산 | 1,379건 |
| **Hilbert Multi-Interval** | **12** | **5** | **103건** |

---

## GeoHash 설계 개선 과정

### 1차: 3×3 고정 셀

```
후보 70건, 결과 4건 (27건 중 23건 누락)
→ 반경 5km를 3.6km로 커버 불가
```

### 2차: 동적 셀 범위 (현재)

```
steps = ceil(5.0 / 1.2) + 1 = 6 → 13×13 = 169개 셀
후보 1,379건, 결과 27건 ✅
```

---

## Hilbert 설계 개선 과정

### 1차: % MAX_PAGE

```
delta > MAX_PAGE → 전체 PageId 커버 → Full Scan 退化
후보 79,081건, 결과 0건
```

### 2차: / RANGE_PER_PAGE + delta 하드코딩

```
delta = steps * steps * 200
PageId 275개, interval 1개, 후보 2,102건 ✅
→ 엉뚱한 지역 포함, delta 하드코딩 한계
```

### 3차: 격자 순회

```
후보 97건 ✅, 검색 37ms
→ 671만 연산 병목
```

### 4차: Multi-Interval Query + 사각형 MBR (현재)

```
격자 순회 → visited[PageId] 마킹 → Interval Merge
PageId 12개, interval 5개
후보 103건 (사각형 MBR), 결과 27건 ✅

원형 필터링은 프론트엔드 위임
→ 엔진은 I/O 최소화에 집중
```

**힐버트 곡선 위 interval 분포:**
```
[3766], [3772~3773], [3775], [3879~3884], [3889~3890]
→ 5개 disjoint interval, PageId 12개만 I/O
```

---

## 파일 구조

```
benchmark/
  Hospital.java              병원 데이터 클래스 (toBytes/fromBytes)
  DummyDataGenerator.java    79,081건 더미 데이터 생성 (SEED=42)
  FullScanBenchmark.java     Full Scan 성능 측정
  GeoHashBenchmark.java      GeoHash 성능 측정
  HilbertBenchmark.java      Hilbert 성능 측정
  BenchmarkRunner.java       3방향 비교 실행
```
