# MiniDB - GeoSpatial Index Engine

> 실제 병원 검색 서비스의 성능 한계에서 출발해 직접 구현한 경량 공간 인덱스 엔진

---

## 배경

실제 배포된 병원 검색 서비스에서 **반경 5km 내 병원 검색 API**를 운영하던 중 두 가지 한계를 경험했습니다.

### 문제 1: MariaDB 공간 인덱스가 오히려 느렸다

```
SPATIAL INDEX (MBRContains) 도입
→ MBRContains 공간 연산 오버헤드
→ 넓은 영역에서 Full Scan보다 2-3배 느림

복합 인덱스 (coordinate_x, coordinate_y) 시도
→ 옵티마이저가 Full Scan 선택
→ FORCE INDEX 강제해도 23배 비효율 (23,000 스캔 / 1,000 반환)

결론: 7만 건 규모에서 Full Scan + BETWEEN이 최적 (30-50ms)
```

### 문제 2: Redis Geohash 캐싱의 한계

```
좌표 기반 캐싱 → 10m 떨어진 사용자도 캐시 미스
Geohash 격자 캐싱 도입 → 캐시 HIT 29-124ms

하지만:
→ Redis 외부 인프라 의존
→ Cold Start 문제 (첫 요청 671ms)
→ 서버별 캐시 독립 (공유 불가)
```

### 해결 방향

```
DB 엔진 레벨에서 직접 공간 클러스터링 구현
→ 외부 인프라 없이
→ 삽입 시점부터 가까운 병원을 같은 페이지에 클러스터링
→ 검색 시 해당 페이지만 읽기
```

---

## 성능 결과

<div align=center>
<img src="https://raw.githubusercontent.com/YOUHEETAE/java-disk-kv-engine/dev/docs/benchmark_chart.png" width="700"/>
</div>

| 건수 | Full Scan | GeoHash | Hilbert |
|------|----------|---------|---------|
| 10,000 | 100ms | 0ms | 29ms |
| 79,081 | 434ms | 0ms | 33ms |
| 500,000 | 768ms | 0ms | 24ms |
| 1,000,000 | 1,177ms | 6ms | 34ms |

- **Full Scan**: 데이터량에 따라 선형 증가 O(N)
- **GeoHash**: 공간 클러스터링으로 데이터 규모 무관 O(1)에 수렴, 최대 **251배** 향상
- **Hilbert**: Multi-Interval Query로 필요한 PageId만 정확히 탐색

### 방식별 PageId 탐색 수 비교 (반경 5km, 강남 기준)

| 방식 | PageId 수 | interval 수 | 후보 수 |
|------|----------|------------|--------|
| **Full Scan** | 10,000 | 1 | 79,081건 |
| **선형 범위** | 275 | 1 | 2,102건 |
| **GeoHash** | 169 | 분산 | 1,379건 |
| **Hilbert Multi-Interval** | **13** | **5** | **103건** |

Page Seek Count 비교 (강남 반경 5km 실측):

| 방식 | PageId 수 | Seek Count |
|------|----------|-----------|
| GeoHash | 169 | 720 |
| **Hilbert Multi-Interval** | **13** | **124** |

Hilbert가 GeoHash 대비 Seek Count **5.8배 적음** → 순차 I/O에 가까움

Hilbert Multi-Interval이 PageId 13개만 읽으면 되는 이유:
원형 영역이 힐버트 곡선 위에서 5개의 disjoint interval로 정확히 표현되기 때문입니다.

```
h값 분포: [3766], [3772~3773], [3775], [3879~3884], [3889~3890]
→ 실제 필요한 PageId 13개만 I/O
```

---

## 핵심 설계

### 아키텍처

```
┌─────────────────────────────────┐
│  API Layer (SpatialRecordManager)│  공간 인덱스 저장/검색
├─────────────────────────────────┤
│  Index Layer (SpatialIndex)      │  GeoHash / Hilbert
├─────────────────────────────────┤
│  Buffer Layer (CacheManager)     │  Write-Back 캐싱
├─────────────────────────────────┤
│  Storage Layer (DiskManager)     │  페이지 기반 디스크 I/O
└─────────────────────────────────┘
```

### 공간 클러스터링

```
삽입 시: 좌표 → GeoHash/힐버트값 → PageId 결정
→ 가까운 병원이 자동으로 같은/인접 페이지에 저장

검색 시: 반경 내 PageId 목록 → 해당 페이지만 읽기
→ Full Scan 없이 필요한 페이지만 I/O
```

### GeoHash 인덱스

```
좌표 → Geohash 문자열 (precision 6, 격자 1.2km)
→ Base32 → long 변환 (공간 근접성 보존)
→ long % MAX_PAGES → PageId

hashCode() 사용 시 공간 근접성 파괴
Base32 → long 변환: 접두사가 같으면 long 값도 가까움 → 클러스터링 달성
```

### Hilbert Multi-Interval Query

```
① 반경 안 격자(x, y) 순회
② 각 격자 → 힐버트값 → PageId 마킹
③ PageId 연속 구간 → Interval Merge
④ disjoint interval별 PageId 범위만 읽기
```

**왜 Multi-Interval인가?**

원형 영역은 힐버트 곡선 위에서 하나의 연속 구간이 아닙니다.

```
힐버트 경계 점프: 사분면 경계에서 힐버트값이 크게 점프
→ 원형이 곡선 위에서 여러 disjoint interval로 쪼개짐

선형 범위 방식: centerH ± delta → 1개 구간 275 PageId (엉뚱한 지역 포함)
Multi-Interval:  5개 interval  → 13 PageId (정확히 필요한 것만)
```

이것이 실제 Hilbert R-Tree가 사용하는 방식입니다.
계산 비용(671만 연산)이 현재 구현의 한계이며, 실제 DB는 R-Tree로 이를 해결합니다.

상세 구현 과정 → [HILBERT_IMPLEMENTATION.md](./mini-db/src/minidb/index/HILBERT_IMPLEMENTATION.md)

---

## 사용법

```java
DiskManager diskManager = new DiskManager("data.db");
CacheManager cacheManager = new CacheManager(diskManager);
SpatialRecordManager manager = new SpatialRecordManager(cacheManager, new GeoHashIndex());

// 삽입
manager.put(37.4979, 127.0276, hospital.toBytes());

// 반경 검색
List<byte[]> candidates = manager.searchRadius(37.4979, 127.0276, 5.0);

// 원형 필터링은 애플리케이션에서
candidates.stream()
    .map(b -> Hospital.fromBytes("", b))
    .filter(h -> GeoUtils.haversine(lat, lng, h.coordinateY, h.coordinateX) <= 5.0)
    .collect(Collectors.toList());

cacheManager.close();
```

---

## 모듈 구조

```
minidb/
  storage/
    Page.java               4KB 페이지
    DiskManager.java        디스크 I/O
    PageLayout.java         슬롯 페이지 구조
  buffer/
    CacheManager.java       Write-Back 캐싱
  api/
    RecordManager.java          Key-Value 저장
    SpatialRecordManager.java   공간 인덱스 저장/검색
  index/
    SpatialIndex.java       인터페이스
    GeoHash.java            순수 계산 로직
    GeoHashIndex.java       SpatialIndex 구현체
    HilbertCurve.java       순수 계산 로직
    HilbertIndex.java       SpatialIndex 구현체 (Multi-Interval)
  benchmark/
    FullScanBenchmark.java      Full Scan 측정
    GeoHashBenchmark.java       GeoHash 측정
    HilbertBenchmark.java       Hilbert 측정
    BenchmarkRunner.java        3방향 비교 실행
  util/
    GeoUtils.java           Haversine 거리 계산
```

---

## 기술 스택

| 항목 | 내용 |
|------|------|
| **언어** | Java 21 |
| **스토리지** | RandomAccessFile (페이지 기반) |
| **외부 의존성** | 없음 |
| **테스트** | JUnit 5 |
| **데이터** | 79,081건 한국 병원 더미 데이터 |

---

## 로드맵

```
✅ Phase 1: Storage (Page, DiskManager, CacheManager)
✅ Phase 2: API (RecordManager, PageLayout)
✅ Phase 3: GeoHash (GeoHash, GeoHashIndex, SpatialRecordManager)
✅ Phase 4: Benchmark (Full Scan vs GeoHash)
✅ Phase 5: Hilbert (HilbertCurve, HilbertIndex, Multi-Interval Query)
✅ Phase 6: 최종 3방향 비교
⬜ Phase 7: 격자 순회 없는 Interval 직접 계산 (Hilbert R-Tree 방향)
```

---

## 라이센스

MIT
