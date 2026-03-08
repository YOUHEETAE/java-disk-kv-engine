# Benchmark 모듈

공간 인덱스 성능 측정 - Full Scan vs GeoHash vs Hilbert 3방향 비교

---

## 벤치마크 구성

### 더미 데이터 벤치마크 (규모별 성능 추세)

| 항목 | 내용 |
|------|------|
| **데이터** | 더미 병원 데이터 (SEED=42, 한국 좌표 범위) |
| **검색 조건** | 강남 좌표 기준 반경 5km |
| **검색 좌표** | lat: 37.4979, lng: 127.0276 |
| **필터링** | 사각형 MBR |

### 실제 데이터 벤치마크 (Spring 연동)

| 항목 | 내용 |
|------|------|
| **데이터** | 실제 한국 병원 데이터 79,081건 |
| **검색 조건** | 반경 5km |
| **측정 방식** | Warm-up 5회 + 실측 50회 평균, 홀짝 교대 실행 |
| **비교 대상** | Full Scan (MariaDB) vs GeoIndex (MiniDB → MariaDB IN 쿼리) |

---

## 더미 데이터 결과 (규모별)

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

## 실제 병원 데이터 결과 (50회 평균)

```
Full Scan 평균:      65ms   (MariaDB WHERE BETWEEN + JOIN)
GeoIndex 총 평균:    60ms
  └ MiniDB 탐색:      0ms   (187 pageId → hospital_code 후보 추출)
  └ MariaDB IN 쿼리:  60ms  (WHERE hospital_code IN (...))
후보 감소:  79,081건 → 1,366건 (98.3% 감소)
```

### 해석

더미 데이터에서는 GeoIndex가 압도적으로 빠르지만, 실제 데이터에서는 5ms 차이로 좁혀집니다. 이유는 두 가지입니다.

**DB IN 쿼리 비용:** 1,366건 IN 쿼리가 생각보다 가볍지 않습니다. Full Scan이 65ms로 빠른 이유는 MariaDB 인덱스와 쿼리 캐시가 이미 최적화되어 있기 때문입니다.

**MiniDB의 실제 기여:** MiniDB 탐색 자체는 0ms입니다. 후보를 98.3% 줄인 덕분에 IN 쿼리 비용도 감소했으며, 캐시 적중 시 IN 쿼리도 추가로 단축될 여지가 있습니다.

---

## PageId 탐색 수 비교 (반경 5km, 강남 기준)

| 방식 | PageId 수 | 후보 수 |
|------|----------|--------|
| Full Scan | 전체 | 79,081건 |
| GeoHash | 187 | 1,366건 |
| **Hilbert Multi-Interval** | **12** | **103건** |

---

## Page Seek Count 비교

```
GeoHash:  PageId 187개 → Seek Count 720
Hilbert:  PageId  12개 → Seek Count 124

Hilbert가 GeoHash 대비 Seek Count 5.8배 적음
```

```
GeoHash:  [5 → 402 → 11 → 390 → ...]  ← 분산된 랜덤 I/O
Hilbert:  [3766 → 3772 → 3773 → ...]  ← 연속 순차 I/O
```

Seek Count = 인접 PageId 간 거리(`|p[i+1] - p[i]|`) 합계
→ 낮을수록 디스크 헤드 이동 최소화 → HDD 환경에서 유리

---

## 파일 구조

```
benchmark/
  Hospital.java              병원 데이터 클래스 (toBytes / fromBytes)
  DummyDataGenerator.java    더미 데이터 생성 (SEED=42)
  FullScanBenchmark.java     Full Scan 측정
  GeoHashBenchmark.java      GeoHash 측정
  HilbertBenchmark.java      Hilbert 측정
  BenchmarkRunner.java       3방향 비교 + Seek Count 비교 실행
  SeekCountBenchmark.java    PageId 목록 → Seek Count 계산
```

---

## 실행 방법

```bash
# 더미 데이터 3방향 비교
mvn exec:java -Dexec.mainClass="geoindex.benchmark.BenchmarkRunner"

# 실제 병원 데이터 비교 (Spring 연동)
GET /benchmark/compare?userLat=37.4979&userLng=127.0276&radius=5.0
```
