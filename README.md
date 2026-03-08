# MiniDB - GeoSpatial Index Engine

> 실제 병원 검색 서비스의 캐싱 한계에서 출발해 직접 구현한 경량 공간 인덱스 엔진

---

## 문제 정의

실제 배포된 병원 검색 서비스에서 **반경 5km 내 병원 검색 API**를 운영하던 중 캐싱 문제를 경험했습니다.

### 문제 1: MariaDB 인덱스로는 해결이 안 됐다

```
SPATIAL INDEX (MBRContains) 도입
→ MBRContains 공간 연산 오버헤드
→ 넓은 영역에서 Full Scan보다 2-3배 느림

복합 인덱스 (coordinate_x, coordinate_y) 시도
→ 옵티마이저가 Full Scan 선택
→ FORCE INDEX 강제해도 23배 비효율 (23,000 스캔 / 1,000 반환)

결론: 7만 건 규모에서 Full Scan + BETWEEN이 최적 (30-50ms)
```

→ 자세한 분석: [INDEX_PERFORMANCE_ANALYSIS.md](./INDEX_PERFORMANCE_ANALYSIS.md)

### 문제 2: 좌표 기반 캐싱은 근본적으로 불가능하다

```
사용자 위치(위도, 경도)를 캐시 키로 사용
→ 좌표는 무한 → 10m 떨어진 사용자도 캐시 미스
→ 같은 지역 사용자 간 캐시 공유 불가
```

### 문제 3: Redis Geohash 격자 캐싱도 한계가 있었다

좌표 캐싱의 한계를 해결하기 위해 Geohash 격자를 캐시 키로 사용하는 방식을 직접 구현했습니다.

```
Geohash precision 5 → 4.9km × 4.9km 격자
인접 3×3 = 9개 격자를 캐시 키로 사용
→ 같은 지역 사용자 간 캐시 공유 가능 (캐시 HIT 시 29-124ms)
```

하지만 새로운 문제가 발생했습니다:

```
문제 1: Redis 캐시 확인 비용
  → MGET(9개 격자) 네트워크 왕복 발생

문제 2: 백그라운드 인접 격자 캐싱 비용
  → Cold Start 시 671ms (9개 격자 동시 캐싱)

문제 3: 외부 인프라 의존
  → Redis 장애 시 서비스 영향
  → 서버별 캐시 독립 (공유 불가)
```

→ 자세한 분석: [GEOHASH_CACHE_OPTIMIZATION.md](./GEOHASH_CACHE_OPTIMIZATION.md)

### 해결 방향

```
외부 인프라(Redis) 없이 캐싱 문제를 해결하려면
→ DB 엔진 레벨에서 직접 공간 클러스터링 구현
→ 삽입 시점부터 가까운 병원을 같은 페이지에 클러스터링
→ pageId = 공간 캐시 키 (같은 지역 = 같은 pageId)
→ JVM 메모리 캐시 → 네트워크 왕복 없음
```

---

## 아키텍처

```
┌─────────────────────────────────────┐
│  Application Layer (Spring)          │
│  SpatialCacheService                 │  pageId → List<HospitalData> JVM 캐시
├─────────────────────────────────────┤
│  API Layer (SpatialRecordManager)    │  공간 인덱스 저장/검색
├─────────────────────────────────────┤
│  Index Layer (SpatialIndex)          │  GeoHash / Hilbert
├─────────────────────────────────────┤
│  Buffer Layer (CacheManager)         │  Write-Back 캐싱
├─────────────────────────────────────┤
│  Storage Layer (DiskManager)         │  페이지 기반 디스크 I/O
└─────────────────────────────────────┘
```

---

## 핵심 설계

### 공간 클러스터링

```
삽입 시: 좌표 → Morton 코드 → pageId 결정
→ 가까운 병원이 자동으로 같은/인접 페이지에 저장

검색 시: 반경 내 pageId 목록 → 해당 페이지만 읽기
→ Full Scan 없이 필요한 페이지만 I/O
```

### GeoHash 인덱스 (Morton 코드 직접 사용)

3단계 설계 개선을 거쳐 현재 구조에 도달했습니다:

```
1차: steps × steps 고정 셀 → 반경 경계 누락
2차: % MAX_PAGES 매핑 → % 연산으로 공간 지역성 파괴
3차: Morton SHIFT → 한국 좌표 특성상 pageId 1~2개로 뭉침
4차: Morton 직접 pageId + sparse 매핑 테이블 → 187개 분산 ✅
```

```
Morton 코드(Z-curve 비트 인터리빙) 값을 직접 pageId로 사용
+ DiskManager sparse 매핑 테이블 (HashMap<pageId, 파일오프셋>)
→ pageId가 6천만이어도 실제 파일 = 데이터 페이지 수 × 4KB
→ 반경 5km = 187개 pageId로 분산 (이전 1~2개 → 187개)
```

→ 설계 개선 상세 기록: [GEOHASH_IMPLEMENTATION.md](./geo-index/src/main/java/geoindex/index/GEOHASH_IMPLEMENTATION.md)

### Hilbert Multi-Interval Query

```
① 반경 안 격자(x, y) 순회
② 각 격자 → 힐버트값 → pageId 마킹
③ pageId 연속 구간 → Interval Merge
④ disjoint interval별 pageId 범위만 읽기
```

힐버트 곡선 위 interval 분포 (강남 반경 5km):
```
[3766], [3772~3773], [3775], [3879~3884], [3889~3890]
→ 5개 disjoint interval, pageId 13개만 I/O
```

### JVM 캐시 (SpatialCacheService)

```
첫 요청:
  MiniDB → pageId 목록 반환 (0ms)
  pageId별 캐시 확인 → MISS
  MariaDB → 전체 데이터 조회
  결과 → Map<pageId, List<HospitalData>> 저장

두 번째 요청 (같은 반경):
  MiniDB → pageId 목록 반환 (0ms)
  pageId별 캐시 확인 → HIT
  MariaDB 왕복 없음 → 즉시 반환
```

Redis 대비 장점:
```
HashMap.get() → 나노초 (MGET 네트워크 왕복 없음)
Cold Start 없음 → 첫 요청에 자연스럽게 캐시 채워짐
외부 인프라 불필요
```

---

## 성능 결과

### 더미 데이터 벤치마크 (1,000회 평균)

> 측정 조건: JVM Warm-up 후 동일 쿼리 1,000회 평균 / 각 실행 전 캐시 초기화

<div align=center>
<img src="https://raw.githubusercontent.com/YOUHEETAE/java-disk-kv-engine/dev/docs/benchmark_chart.png" width="700"/>
</div>

| 건수 | Full Scan | GeoHash | Hilbert |
|------|----------|---------|---------|
| 10,000 | 100ms | 6.8ms | 29ms |
| 20,000 | 133ms | 6.8ms | 33ms |
| 30,000 | 259ms | 6.8ms | 32ms |
| 50,000 | 292ms | 6.8ms | 33ms |
| 79,081 | 434ms | 6.8ms | 33ms |
| 100,000 | 528ms | 6.8ms | 47ms |
| 200,000 | 660ms | 6.8ms | 24ms |
| 500,000 | 768ms | 6.8ms | 24ms |
| 1,000,000 | 1,177ms | 6ms | 34ms |

> GeoHash avg: **6.8ms** (P95: 9.1ms) / Hilbert avg: **33ms** (P95: 41ms)

- **Full Scan**: 데이터량에 따라 선형 증가 O(N)
- **GeoHash**: 공간 밀도에 의존 O(P) → 대규모 데이터에서도 일정한 검색 성능 유지
- **Hilbert**: Multi-Interval Query로 필요한 pageId만 정확히 탐색

### 실제 병원 데이터 벤치마크 (50회 평균)

> 측정 조건: Warm-up 5회 제외 / 홀짝 교대 실행으로 캐시 편향 제거

| 방식 | geo-index 탐색 | DB 조회 | 총 시간 | 후보 수 |
|------|--------------|--------|--------|--------|
| Full Scan | - | 65ms | 65ms | 79,081건 스캔 |
| GeoIndex | 0ms | 60ms | 60ms | 1,366건 |

> GeoIndex geo-index 탐색 0ms, 후보 **98.3% 감소** (79,081건 → 1,366건)

warm 상태 DB에서 총 시간 차이가 작은 이유: MariaDB 버퍼풀 캐싱으로 두 방식 모두 안정화됨.
대규모 데이터 또는 콜드 상태에서는 Full Scan이 선형 증가하는 반면 GeoIndex는 일정 유지.

### PageId 탐색 수 비교 (반경 5km, 강남 기준)

| 방식 | PageId 수 | interval 수 | 후보 수 |
|------|----------|------------|--------|
| Full Scan | 전체 | 1 | 79,081건 |
| 선형 범위 | 275 | 1 | 2,102건 |
| GeoHash | 187 | 분산 | 1,366건 |
| Hilbert Multi-Interval | **13** | **5** | **103건** |

### Page Seek Count 비교 (강남 반경 5km)

| 방식 | PageId 수 | Seek Count |
|------|----------|-----------|
| GeoHash | 169 | 720 |
| **Hilbert Multi-Interval** | **13** | **124** |

Hilbert가 GeoHash 대비 Seek Count **5.8배 적음** → 순차 I/O에 가까움

---

## Production Integration

MiniDB는 트랜잭션 및 동시성 제어를 지원하지 않으므로 Primary Database 대체가 아닌 **공간 필터 + JVM 캐시** 역할로 사용합니다.

```
[요청]
  ↓
[MiniDB] pageId 목록 계산 (0ms)
  ↓
[SpatialCacheService] pageId 캐시 확인
  ├─ HIT → 즉시 반환 (MariaDB 왕복 없음)
  └─ MISS → [MariaDB] WHERE hospital_code IN (...) + JOIN
              → 결과를 pageId 단위로 캐시 저장
```

**운영 전략:**
```
병원 데이터는 주 1회 대량 배치 업데이트
→ 매주 MiniDB 전체 재빌드 (delete 불필요)
→ 재빌드 중 이전 파일로 서비스 유지
→ 완료 후 파일 교체 (atomic rename)
→ JVM 캐시도 재빌드 시 자동 초기화
```

---

## 모듈 구조

```
geo-index/
  storage/
    Page.java               4KB 페이지
    DiskManager.java        sparse 매핑 테이블 기반 디스크 I/O
    PageLayout.java         슬롯 페이지 구조
  buffer/
    CacheManager.java       Write-Back 캐싱
  api/
    RecordManager.java          Key-Value 저장
    SpatialRecordManager.java   공간 인덱스 저장/검색
  index/
    SpatialIndex.java       인터페이스
    GeoHash.java            Morton 코드 인코딩/디코딩
    GeoHashIndex.java       Morton 직접 pageId 매핑
    HilbertCurve.java       힐버트 곡선 계산
    HilbertIndex.java       Multi-Interval Query 구현
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
| **데이터** | 79,081건 한국 병원 데이터 |

---

## 로드맵

```
✅ Phase 1: Storage (Page, DiskManager, CacheManager)
✅ Phase 2: API (RecordManager, PageLayout)
✅ Phase 3: GeoHash (GeoHash, GeoHashIndex, SpatialRecordManager)
✅ Phase 4: Benchmark (Full Scan vs GeoHash vs Hilbert)
✅ Phase 5: Hilbert Multi-Interval Query + Seek Count 비교
✅ Phase 6: DiskManager sparse 매핑 테이블
✅ Phase 7: Morton 코드 직접 pageId 매핑 (pageId 분산 187개)
✅ Phase 8: 실제 병원 데이터 연동 + A/B 벤치마크 (50회 평균)

⬜ Phase 9: SpatialCacheService (JVM 캐시)
    9-1. Map<pageId, List<HospitalData>> 설계
    9-2. pageId 단위 캐시 HIT/MISS 처리
    9-3. MISS pageId만 MariaDB 조회
    9-4. 캐시 적중률 측정 및 문서화
    9-5. Full Scan / GeoIndex / GeoIndex+Cache 3방향 비교
```

---

## 라이센스

MIT
