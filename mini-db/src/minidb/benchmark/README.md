# Benchmark 모듈

공간 인덱스 성능 측정 - Full Scan vs GeoHash vs 힐버트 커브 비교

---

## 목표

병원 위치 데이터 반경 5km 검색 성능을 3가지 방식으로 비교

```
Full Scan        → 기준선
GeoHash          → 공간 인덱스 (격자 기반)
힐버트 커브      → 공간 인덱스 (곡선 기반)
```

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

---

## 더미 데이터 구조

실제 `hospital_main` 테이블 구조 기반

| 필드 | 타입 | 비고 |
|------|------|------|
| hospital_code | String | H00001 ~ H79081 |
| coordinate_x | double | 경도 (126.0 ~ 129.5) |
| coordinate_y | double | 위도 (33.0 ~ 38.5) |
| doctor_num | String | 랜덤 |
| hospital_address | String | 랜덤 |
| hospital_name | String | 랜덤 |
| hospital_tel | String | 랜덤 |
| district_name | String | 랜덤 |
| hospital_homepage | String | 랜덤 |
| province_name | String | 랜덤 |

**직렬화 방식:** 바이너리 (ByteBuffer)

---

## 성능 측정 결과

### 최종 3방향 비교

| 건수 | Full Scan | GeoHash | Hilbert | GeoHash 개선율 | Hilbert 개선율 |
|------|----------|---------|---------|--------------|--------------|
| 10,000 | 103ms | 0ms | 16ms | - | 6배 |
| 20,000 | 164ms | 3ms | 0ms | 54배 | - |
| 30,000 | 199ms | 0ms | 0ms | - | - |
| 50,000 | 290ms | 0ms | 16ms | - | 18배 |
| 79,081 | 417ms | 0ms | 0ms | - | - |
| 100,000 | 487ms | 1ms | 0ms | 487배 | - |
| 200,000 | 534ms | 0ms | 4ms | - | 133배 |
| 500,000 | 729ms | 5ms | 5ms | 145배 | 145배 |
| 1,000,000 | 1,257ms | 5ms | 16ms | **251배** | **78배** |

### 후보 수 비교 (79,081건 기준)

| 방식 | PageId 조회 수 | 후보 수 | 결과 |
|------|-------------|--------|------|
| Full Scan | 전체 | 79,081건 | 27건 |
| GeoHash | 169개 (13×13 셀) | 1,379건 | 27건 ✅ |
| Hilbert | 268개 (선형 범위) | 2,102건 | 27건 ✅ |

---

## GeoHash 설계 개선 과정

### 1차 시도: 3×3 고정 셀

```
중심 셀 + 주변 8개 = 9개 셀
커버 범위: 3.6km × 3.6km
```

**결과:**
```
후보: 70건
결과: 4건 (27건 중 23건 누락)
```

**문제:** 반경 5km를 3×3 셀(3.6km)로 커버 불가

### 2차 시도: 동적 셀 범위 확장

```
steps = ceil(radiusKm / CELL_SIZE_KM) + 1
      = ceil(5.0 / 1.2) + 1 = 6

범위: -6 ~ +6 = 13×13 = 169개 셀
커버: 15.6km × 15.6km
```

**결과:**
```
후보: 1,379건
결과: 27건 ✅ (Full Scan과 일치)
```

---

## 힐버트 설계 개선 과정

### 1차 시도: % MAX_PAGE

```
힐버트값 % 10,000 → PageId
delta = steps * steps = 71,824
```

**결과:**
```
후보: 79,081건 (Full Scan 退化)
결과: 0건
```

**문제:** delta가 MAX_PAGE를 초과 → 전체 PageId 커버

### 2차 시도: / RANGE_PER_PAGE + delta 보정

```
힐버트값 / RANGE_PER_PAGE → PageId  (선형 분할로 연속성 보존)
delta = steps * steps * 2
```

**결과:**
```
후보: 22건
결과: 19건 (8건 누락)
```

**문제:** 힐버트 경계 점프(최대 12,490,934)를 delta가 커버 못함

### 3차 시도: delta 실측 기반 보정

```
강남 기준 8방향 실측 → 최대 delta: 12,490,934
steps * steps * 200 = 14,364,800  ← 커버
```

**결과:**
```
후보: 2,102건
결과: 27건 ✅ (Full Scan과 일치)
```

구현 상세 → [HILBERT_IMPLEMENTATION.md](../index/HILBERT_IMPLEMENTATION.md)

---

## 캐시 효과 제거

```
캐시 초기화 전: 메모리에서 읽음 → 왜곡된 수치
캐시 초기화 후: 디스크에서 읽음 → 실제 I/O 성능

flush() → clearCache() 순서 필수
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

---

## 향후 개선 가능성

```
힐버트 동적 delta:
→ 현재 강남 기준 하드코딩 (steps * steps * 200)
→ 좌표마다 경계 점프 크기가 다름
→ 검색 시점에 8방향 실측으로 delta 동적 계산 시 정확도 향상

교차 계산 알고리즘:
→ 반경과 실제 교차하는 셀만 읽기
→ GeoHash 후보 1,379건 → 추가 감소 가능
→ Hilbert 후보 2,102건 → GeoHash 수준으로 감소 가능
```
