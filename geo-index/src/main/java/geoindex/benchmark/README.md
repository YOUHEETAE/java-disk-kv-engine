# Benchmark 모듈

공간 인덱스 성능 측정 - Full Scan vs GeoHash

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
| **측정 방식** | Warm-up 5회 제외 / 홀짝 교대 실행으로 캐시 편향 제거 |
| **비교 대상** | Full Scan / GeoIndex / GeoIndex+Cache 3방향 |

---

## 더미 데이터 결과 (규모별)

| 건수 | Full Scan | GeoHash |
|------|----------|---------|
| 10,000 | 100ms | < 1ms |
| 20,000 | 133ms | < 1ms |
| 30,000 | 259ms | 2ms |
| 50,000 | 292ms | < 1ms |
| 79,081 | 434ms | < 1ms |
| 100,000 | 528ms | < 1ms |
| 200,000 | 660ms | 3ms |
| 500,000 | 768ms | < 1ms |
| 1,000,000 | 1,177ms | 6ms |

> 두 경로는 저장하는 값이 다르다. Full Scan은 `Hospital` 레코드 전체를 담고
> 8만 건 전부에 haversine을 돌리며, GeoHash 경로는 병원 코드만 담고 후보
> 페이지만 읽는다. 배수에는 색인 효과와 **저장 대상 차이**가 함께 들어 있다.

---

## 실제 병원 데이터 결과

### GeoIndex 단독 (50회 평균)

```
Full Scan 평균:      65ms   (MariaDB WHERE BETWEEN + JOIN)
GeoIndex 총 평균:    60ms
  └ MiniDB 탐색:      < 1ms (187 pageId → hospital_code 후보 추출)
  └ MariaDB IN 쿼리:  60ms  (WHERE hospital_code IN (...))
후보 감소: 79,081건 → 1,366건 (98.3% 감소)
```

**총 시간 차이가 작은 이유:**

```
MariaDB 버퍼풀이 79,081건을 전부 메모리에 상주
→ Full Scan도 디스크 I/O 없이 메모리 스캔
→ GeoIndex의 IN(1,366건) 쿼리 오버헤드 ≈ Full Scan BETWEEN 비용

GeoIndex의 실제 기여:
  MiniDB 탐색 < 1ms + 후보 98.3% 감소
  → 캐시 계층의 캐시 키(pageId) 역할
  → 데이터 폭증 시 Full Scan O(N) vs GeoIndex O(P) 격차 폭발
```

**데이터 폭증 시 GeoIndex 우위:**

| 건수 | Full Scan | GeoIndex | 개선율 |
|------|----------|---------|--------|
| 100,000 | 528ms | 7ms | 75배 |
| 1,000,000 | 1,177ms | 6ms | 118배 |

---

### GeoIndex+Cache 3종 (100회 평균)

캐시 시나리오별로 현실적인 성능 차이를 측정합니다.

#### Random Query (완전 랜덤)

```
시나리오: 전국 병원 분포 반경 내 완전 랜덤 좌표 100회
측정 대상: 캐시 재사용이 전혀 없는 Worst Case

Full Scan         │  82ms
GeoIndex          │  88ms
GeoIndex+Cache    │  70ms  (HIT: 558 / MISS: 9442 / HIT율: 5.8%)
개선율            │   1.2x
```

**해석:** 완전 랜덤 좌표는 pageId가 매번 달라져 캐시 재사용이 불가합니다.
HIT율 5.8%는 캐시가 거의 작동하지 않는 이론적 하한선입니다.

---

#### Hotspot Query (서울 주요 지역 순환)

```
시나리오: 강남/홍대/종로 등 서울 주요 10개 지역 순환 100회
측정 대상: 실제 서비스에서 인기 지역 재조회 Best Case

Full Scan         │  84ms
GeoIndex          │  92ms
GeoIndex+Cache    │   2ms  (HIT: 9854 / MISS: 146 / HIT율: 98.6%)
개선율            │  46.8x
```

**해석:** 같은 지역 재요청은 pageId가 동일 → 첫 요청 이후 전부 HIT.
HIT 시 MariaDB 왕복 없이 JVM HashMap에서 나노초 반환.

---

#### Mixed Query (현실적 서비스)

```
시나리오: 70% Hotspot + 30% Random 혼합 100회
측정 대상: 실제 서비스 트래픽 패턴

Full Scan         │  87ms
GeoIndex          │  95ms
GeoIndex+Cache    │   3ms  (HIT: 10519 / MISS: 450 / HIT율: 95.9%)
개선율            │  24.6x
```

**해석:** 실제 서비스에서 인기 지역 요청이 대부분을 차지하므로
Mixed가 가장 현실적인 시나리오입니다. 24.6배 개선 달성.

---

### 3종 종합

| 시나리오 | 개선율 | HIT율 | 특징 |
|----------|--------|-------|------|
| Random | 1.2x | 5.8% | Worst Case (캐시 재사용 없음) |
| **Mixed** | **24.6x** | **95.9%** | **현실적 서비스 트래픽** |
| Hotspot | 46.8x | 98.6% | Best Case (인기 지역 순환) |

---

## PageId 탐색 수 비교 (반경 5km, 강남 기준)

| 방식 | PageId 수 | 후보 수 |
|------|----------|--------|
| Full Scan | 전체 | 79,081건 |
| GeoHash | 247 | 1,366건 |

> 247칸 중 실재하는 칸은 약 52개다. 나머지는 반경을 덮는 격자에서 나온
> 번호일 뿐 아무도 쓴 적이 없다. 이 비율은 [Index 모듈 README](../index/README.md)
> 참고.

---

## Seek Count 측정에 대하여

곡선별 Seek Count 비교표가 있었으나 제거했다. `|pageId[i+1] − pageId[i]|`를
재고 있었는데, 이는 **인덱스가 만든 번호 사이의 거리**일 뿐 파일 배치와 무관하다.
디스크 seek을 재려면 `pageMap`이 배정한 **파일 오프셋 간 거리**를 봐야 한다.

flush를 pageId 순으로 정렬하도록 바꾼 뒤에도 이 지표는 변하지 않는다 —
측정 대상이 애초에 달랐기 때문이다. 오프셋 기준 측정은 별도 과제로 남아 있다.

---

## 파일 구조

```
benchmark/
  Hospital.java                  병원 데이터 클래스 (toBytes / fromBytes)
  DummyDataGenerator.java        더미 데이터 생성 (SEED=42)
  FullScanBenchmark.java         Full Scan 측정
  GeohashBenchmark.java          GeoHash 측정
  BenchmarkRunner.java           규모별 비교 실행

spring-app/
  HospitalSearchBenchmark.java   실제 병원 데이터 3종 벤치마크
  BenchmarkController.java       REST API (GET /benchmark/compare)
```

---

## 실행 방법

```bash
# 더미 데이터 3방향 비교
mvn exec:java -Dexec.mainClass="geoindex.benchmark.BenchmarkRunner"

# 실제 병원 데이터 3종 벤치마크 (Spring 연동)
GET /benchmark/random?userLat=37.4979&userLng=127.0276&radius=5.0&rounds=100
GET /benchmark/hotspot?radius=5.0&rounds=100
GET /benchmark/mixed?radius=5.0&rounds=100
```
