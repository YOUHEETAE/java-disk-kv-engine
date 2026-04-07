# MiniDB — Spatial Page Cache Engine

> **위치 기반 병원 검색에서 반경 쿼리는 동일 지역 요청이 반복되는 특성이 있다.**
> 하지만 기존 구조는 매번 DB를 조회한다.
>
> → Spatial Index로 좌표를 pageId로 클러스터링
> → pageId 단위 JVM Cache로 DB 접근 제거
> → 외부 인프라(Redis) 없이 서비스 내 메모리만으로 해결

---

## 배경

위치 기반 병원 검색 서비스에서 반경 기반 거리 조회(Radius Query) 성능 저하 문제를 경험했다.

```
시도 1: MariaDB SPATIAL INDEX (MBRContains)
  LEFT JOIN 환경에서 공간 연산 오버헤드로 인해
  옵티마이저가 인덱스 대신 Full Scan을 선택 (30~50ms)
  FORCE INDEX 강제 시: 23,000 스캔 / 1,000 반환 = 23배 비효율

시도 2: 복합 인덱스 (coordinate_x, coordinate_y)
  경도 범위만으로는 선택도 29% → 랜덤 I/O 비용 > Full Scan 비용
  JOIN 환경에서 옵티마이저가 인덱스를 포기 → Full Scan

결론: 7만 건 + JOIN 환경에서 DB 공간 인덱스는 효과 없음
  → Full Scan + BETWEEN이 오히려 최적 (30~50ms)

시도 3: Redis Geohash 캐싱
  DB 조회는 줄였으나 네트워크 왕복 지연 (29~124ms)
  캐시 조회 자체가 느리면 캐싱의 의미가 반감됨
```

**근본 문제**: 공간 인덱스가 DB 안에서 동작하지 않는다면, DB 밖에서 직접 구현해야 한다.


```
해결: 커스텀 Geohash 공간 인덱스 엔진 (MiniDB) 직접 구현
  → GeoHash pageId 클러스터링으로 "같은 지역 = 같은 캐시 키" 보장
  → 네트워크 왕복 없이 JVM 내부에서 공간 인덱스 처리
  → pageId 단위 JVM 캐시로 DB 접근 제거
  → JVM 캐시 결합 시 최대 46.8x 성능 개선
```

**왜 순수 Java 엔진으로 분리했는가:**

GeoHash Morton 코드 계산, pageId 클러스터링, Page 슬롯 구조 등 공간 인덱스 구현 세부사항을 Spring 서비스에서 격리하기 위해서다.
Spring은 `search() / putCache() / rebuild()` 세 가지만 알면 되고, 내부 인덱스 구현이 바뀌어도 서비스 코드는 변경이 없다.

---

## 핵심 인사이트

> Spatial Index 자체는 DB 쿼리 성능을 크게 개선하지 않을 수 있다.
>
> 실제 서비스에서 MariaDB 버퍼풀이 데이터를 메모리에 상주시키면
> Full Scan과 GeoIndex의 DB 조회 시간 차이는 크지 않다.
>
> 하지만 Spatial Index가 제공하는 pageId는
> **"같은 지역 = 같은 pageId"** 라는 캐시 키가 된다.
>
> pageId 단위로 캐시하면 DB 접근 자체를 제거할 수 있다.
>
> **→ DB 쿼리를 빠르게 만드는 것이 아니라, DB를 아예 안 보는 것.**

---

## 아키텍처

```mermaid
flowchart TD
    REQ([HTTP 요청]) --> SCS

    subgraph Spring["Spring Application"]
        SCS["SpatialCacheService\nsearch(lat, lng, radius, batchLoader)"]
    end

    subgraph Engine["geo-index Engine (순수 Java)"]
        subgraph API["API Layer"]
            SCE["SpatialCacheEngine\nsearch(batchLoader) / rebuild / clearCache"]
            SRM["SpatialRecordManager\nsearchRadiusCodesByPageId / searchRadius / put / rebuild"]
        end

        IDX["GeoHashIndex\nMorton 코드 → pageId"]

        subgraph Storage["Storage Layer"]
            CM["CacheManager\nWrite-Back + rebuild"]
            DM["DiskManager\nsparse 매핑 + atomic rename"]
            PG["Page 4KB"]
        end

        SCE --> SRM
        SRM --> IDX
        SRM --> CM
        CM --> DM
        DM --> PG
    end

    DB[("MariaDB")]

    SCS -->|batchLoader 전달| SCE
    SCE -->|HIT| RES([즉시 반환])
    SCE -->|MISS → batchLoader 실행| DB
    DB -->|codes→data Map 반환| SCE
    SCE -->|캐시 저장 후| RES
```

---

## 핵심 설계

### Storage 레이어

```
Page (4KB) → DiskManager (sparse 매핑 테이블) → CacheManager (Write-Back)

pageId가 6천만이어도 실제 파일 = 데이터 페이지 수 × 4KB
→ Morton 코드를 직접 pageId로 사용 가능
```

### GeoHash 인덱스 (Morton 코드 직접 사용)

3단계 설계 개선을 거쳐 현재 구조에 도달했습니다:

```
1차: steps × steps 고정 셀 → 반경 경계 누락
2차: % MAX_PAGES 매핑 → % 연산으로 공간 지역성 파괴
3차: Morton SHIFT → 한국 좌표 특성상 pageId 1~2개로 뭉침
4차: Morton 직접 pageId + sparse 매핑 테이블 → 187개 분산 ✅
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
→ 5개 disjoint interval, pageId 12개만 I/O
```

### JVM 캐시 + rebuild()

```
첫 요청:
  MiniDB → pageId 목록 반환 (< 1ms)
  pageId별 캐시 확인 → MISS
  MariaDB → 전체 데이터 조회
  결과 → putCache() → JVM 캐시 저장

두 번째 요청 (같은 반경):
  MiniDB → pageId 목록 반환 (< 1ms)
  pageId별 캐시 확인 → HIT
  MariaDB 왕복 없음 → 즉시 반환
```

배치 업데이트 시:
```
spatialRecordManager.rebuild(srm ->
    hospitalRepo.findAllCodes().forEach(h ->
        srm.put(h.getLat(), h.getLng(), h.getCode().getBytes())
    )
);
→ atomic rename으로 기존 파일 교체 + JVM 캐시 초기화
→ 요청 중단 없음
```

### 캐시 워밍업

```
문제: 재시작 / rebuild 후 JVM 캐시 초기화
  → 모든 요청 MISS → DB 왕복 폭증 (cold start)

해결: WarmupStore — pageId별 접근 횟수를 디스크에 영속
  서버 운영 중 → pageId 접근 횟수 누적
  종료 시      → warmup.store 파일에 저장
  재시작 시    → Top N pageId DB 조회 → JVM 캐시 선제 적재
               → 첫 요청부터 HIT 가능
```

```
효과:
  핫스팟 pageId 3,000개 워밍업 → 16,000건 병원 데이터 선적재
  → 서울/수도권 요청 대부분 cold start 없이 즉시 HIT
  → rebuild 후에도 동일하게 워밍업 재실행
```

---

## 성능 결과

### 더미 데이터 벤치마크 (1,000회 평균)

> 측정 조건: JVM Warm-up 후 동일 쿼리 1,000회 평균 / 각 실행 전 캐시 초기화

<div align=center>
<img src="https://raw.githubusercontent.com/YOUHEETAE/java-disk-kv-engine/dev/docs/benchmark_chart.png" width="700"/>
</div>

- **Full Scan**: 데이터량에 따라 선형 증가 O(N)
- **GeoHash**: 공간 밀도에 의존 O(P) → 대규모 데이터에서도 일정한 검색 성능 유지
- **Hilbert**: Multi-Interval Query로 필요한 pageId만 정확히 탐색

### 실서비스 벤치마크 (실제 한국 병원 79,081건)

> 측정 조건: Warm-up 5회 제외 / 홀짝 교대 실행으로 캐시 편향 제거 / 3종 시나리오 100회

<div align=center>
<img src="https://raw.githubusercontent.com/YOUHEETAE/java-disk-kv-engine/dev/docs/production_benchmark_chart.png" width="700"/>
</div>

**GeoIndex 단독이 Full Scan과 유사한 이유:**

```
79,081건은 MariaDB 버퍼풀에 전부 상주 → 두 방식 모두 메모리 스캔
IN (1,366건) 쿼리 오버헤드 ≈ BETWEEN 범위 스캔 비용

→ GeoIndex의 실제 역할 = 후보 감소가 아니라 pageId 단위 캐시 키 제공
```

**데이터가 폭증할 경우 GeoHash가 빛을 발한다:**

```
10만 건  → Full Scan 528ms  / GeoHash  7ms  →  75배
100만 건 → Full Scan 1177ms / GeoHash  6ms  → 118배

→ 데이터가 버퍼풀을 초과하는 순간 디스크 I/O 차이가 폭발적으로 벌어짐
```

**시나리오별 해석:**

```
Random (Worst Case):    완전 랜덤 좌표 = 캐시 재사용 불가 → HIT  5.8% →  1.2x
Mixed  (현실적 서비스):  70% 핫스팟     → HIT 95.9%        → 24.6x
Hotspot (Best Case):    서울 주요 지역 순환 → HIT 98.6%    → 46.8x
```

### JMeter 동시 요청 벤치마크 (실서비스 50 스레드)

> 측정 조건: JMeter 50 concurrent threads / 서울 핫스팟 50개 좌표 순환 / 반경 3km
> 비교 대상: Batch Load (`search(batchLoader)`) vs 개별 Load (`searchV1`)

```
Batch Load (search with batchLoader):
  평균 응답시간:  1,490ms
  처리량:        32.4 req/s

개별 Load (searchV1 — MISS 별 개별 DB 조회):
  평균 응답시간:  3,400ms
  처리량:        14.3 req/s

→ 동시 요청 환경에서 Batch Load가 약 2.3x 빠름
```

**개선 원인 분석:**
```
searchV1:
  동시 MISS → pageId별 개별 DB 조회
  N개 MISS → N번 DB 왕복 (커넥션 풀 경합)

Batch Load:
  모든 MISS codes → 단 1회 IN 쿼리 (커넥션 1개)
  pendingLoads → 동일 pageId 중복 쿼리 방지
  → DB 커넥션 경합 최소화 + 쿼리 수 감소
```

---

## Production Integration

MiniDB는 트랜잭션 및 동시성 제어를 지원하지 않으므로 Primary Database 대체가 아닌 **공간 필터 + JVM 캐시** 역할로 사용합니다.

```
[요청]
  ↓
[MiniDB] pageId 목록 계산 (< 1ms)
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
→ 재빌드 중 이전 파일로 서비스 유지 (atomic rename)
→ 완료 후 파일 교체 + JVM 캐시 자동 초기화
```

**Thundering Herd 방지 (Batch Loading Cache):**
```
rebuild 완료 직후 동시 MISS가 몰리면 동일 pageId에 중복 DB 쿼리 발생 가능

→ Loading Cache + Batch Load 패턴으로 해결 (Caffeine / Guava 방식)

Phase 1 — 분류:
  각 pageId에 대해 getOrMiss() → HIT / MISS 판단
  MISS pageId: pendingLoads.putIfAbsent(pageId, future)
    → winner: toLoad에 등록 (이 스레드가 로딩 담당)
    → waiter: waitFuture에 등록 (winner 결과 대기)
  winner는 putIfAbsent 후 double-check:
    재확인 HIT → future 즉시 완료 (다른 스레드가 먼저 put한 경우)

Phase 2 — Batch Load:
  toLoad의 모든 codes를 flatten + distinct → DB 단 1회 조회
  결과를 pageId별로 분배 → putCache + future.complete

Phase 3 — 수집:
  HIT → hitResults에서 직접
  winner → myFuture.getNow() (이미 완료)
  waiter → waitFuture.join() (winner 완료 대기)
```

**핵심 트레이드오프:**
```
synchronized만 있는 경우:
  동시 MISS → 각 스레드가 독립적으로 DB 조회 (중복 쿼리 발생)
  정합성은 보장 (덮어쓰기)

Batch Load + pendingLoads:
  동일 pageId 중복 DB 쿼리 방지
  모든 MISS 코드를 1회 쿼리로 처리
  → Bug 10: putIfAbsent 후 double-check 필요 (상세: CONCURRENCY.md)
```

### Spring MVC 연동

순수 Spring MVC 프로젝트에 Maven 로컬 빌드로 연동하여 **실제 서비스에 적용 중**이다.

```bash
# geo-index 엔진 로컬 빌드
mvn install
```

```xml
<!-- Spring MVC 프로젝트 pom.xml -->
<dependency>
    <groupId>geoindex</groupId>
    <artifactId>geo-index</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

```java
// GeoIndexConfig — Storage / Buffer / API 레이어 빈 등록
@Bean public DiskManager diskManager() { ... }
@Bean public CacheManager cacheManager() { ... }
@Bean public SpatialRecordManager spatialRecordManager() { ... }
@Bean public WarmupStore warmupStore() { ... }

// SpatialCacheService — 배치 로더 람다 전달
List<HospitalWebResponse> results = spatialCacheEngine.search(
    lat, lng, radiusKm,
    codes -> hospitalJdbcRepository.findByHospitalCodes(codes)
               .stream()
               .collect(Collectors.toMap(HospitalWebResponse::getHospitalCode, h -> h))
);
// HIT/MISS 분류 → 배치 DB 조회 → 캐시 저장 → 결과 반환이 search() 내부에서 완결
```

### SpatialCacheEngine — 최상단 API

Spring은 이 메서드들만 알면 된다. 내부 인덱스 구현이 바뀌어도 서비스 코드는 변경 없다.

| 메서드 | 용도 |
|--------|------|
| `search(lat, lng, radiusKm, batchLoader)` | 반경 검색 — MISS codes 배치 DB 조회 → 캐시 저장 → 결과 반환 (권장) |
| `search(lat, lng, radiusKm)` | 반경 검색 — HIT/MISS 판단만, DB 조회는 호출자 책임 |
| `putCache(pageId, data)` | MISS 후 DB 결과 JVM 캐시 저장 |
| `rebuild(loader)` | 파일 재구축 + JVM 캐시 초기화 (atomic rename) |
| `getWarmupTargets(n)` | Top N pageId + codes 반환 (워밍업용) |
| `persistWarmup()` | 히트 카운트 디스크 저장 (종료 시 호출) |
| `getMetrics()` | 전 레이어 메트릭 스냅샷 조회 |

위 성능 수치는 이 연동 환경에서 실제 한국 병원 데이터 79,081건으로 측정한 결과다.

---

## 동시성 이슈 해결

### 왜 이 엔진은 동시성이 특히 중요한가

```
일반 캐시:
  잘못된 캐시 → TTL 만료 후 자동 복구 ✅

이 엔진:
  put() → pageId 계산 → Page에 영구 기록
  한번 잘못 기록된 Page = rebuild() 전까지 영원히 틀린 결과
  데이터 정확성 = 생명
```

### 해결한 버그 (Bug 1~10)

총 10개의 동시성 버그를 발견하고 수정했다. 핵심은 **상위 계층의 락이 하위 계층의 thread-safety를 보장하지 않는다**는 것이다.

```
SpatialRecordManager (Page 레벨 락 ✅)
        ↓
  CacheManager        (flush 동기화 ❌ → Bug 8)
        ↓
  DiskManager         (RandomAccessFile 동기화 ❌ → Bug 5, 6)
```

계층 전체를 독립적으로 보호해야 한다. 상세 내용은 [CONCURRENCY.md](./CONCURRENCY.md) 참고.

### 검증 방법론 — compare 엔드포인트

동시성 버그는 단독 스레드에서 재현되지 않는다. 다음 방식으로 검증했다.

```
GET /loadtest/compare?lat=&lng=&radius=

동일 좌표로 FullScan / GeoIndex 동시 호출
→ 결과를 hospital_code Set으로 비교
→ 누락 건수 로그 출력

스레드 1   → 재현 안 되면 로직 버그
스레드 500 → 재현되면 동시성 버그 확정
```

### 검증 결과 (스레드 500 동시)

```
수정 전:
  FullScan 5040건 | GeoIndex 4610건 | 누락 430건 ❌

수정 후:
  비교 lat=37.5263327 lng=127.0274689
  FullScan: 6460건 | GeoIndex: 6460건 | 누락: 0건 ✅

  전체 100건 비교 → 동시성으로 인한 누락 0건 ✅
```

→ 상세 내용: [CONCURRENCY.md](./CONCURRENCY.md)

---

## 설계 범위 및 한계점

이 엔진은 **단일 노드 워크로드**를 전제로 설계되었다.

| 한계 | 내용 |
|------|------|
| **캐시 불일치** | JVM 캐시는 노드 로컬 — 다중 서버 환경에서 노드마다 캐시 상태가 다르다 |
| **rebuild 전파 없음** | `rebuild()`는 실행한 노드의 파일과 캐시만 교체 — 다른 노드에는 전파되지 않는다 |
| **Thundering Herd** | 노드 간 동시 MISS 시 각 노드가 독립적으로 DB를 조회 — 단일 노드 내 pendingLoads는 무효 |

> 분산 환경에서의 조율은 상위 인프라 레이어(로드밸런서 전략, 외부 캐시 등)에서 처리해야 한다.

---

## 모듈 구조

```
geo-index/
  storage/
    Page.java               4KB 페이지
    DiskManager.java        sparse 매핑 테이블 + atomic rename rebuild
    PageLayout.java         슬롯 페이지 구조 (절대 위치 읽기/쓰기)
  buffer/
    CacheManager.java       Write-Back 캐싱 + rebuild + computeIfAbsent
  api/
    SpatialCacheEngine.java     최상단 API — JVM 캐시 (getOrMiss / put / clearCache)
    SpatialRecordManager.java   파일 검색 / 저장 / rebuild
    PageResult.java             캐시 조회 결과 값 객체
    RecordId.java               레코드 물리 위치 값 객체 (pageId + slotId)
    RecordManager.java          Key-Value 저장
  cache/
    PageCacheStore.java         LinkedHashMap LRU 기반 캐시 인프라
    CachePolicy.java            TTL / maxSize 정책
    CacheEntry.java             캐시 값 래퍼 (데이터 + 만료시각)
    WarmupStore.java            pageId별 접근 횟수 추적 + 디스크 영속
  metric/
    EngineMetrics.java          AtomicLong 카운터 저장소 (전 레이어 공유)
    MetricsSnapshot.java        특정 시점 불변 메트릭 DTO
  index/
    SpatialIndex.java       인터페이스
    GeoHash.java            Morton 코드 인코딩 (toMorton / interleave)
    GeoHashIndex.java       Morton 직접 pageId 매핑
    HilbertCurve.java       힐버트 곡선 계산
    HilbertIndex.java       Multi-Interval Query 구현
    HilbertIndexDebug.java  힐버트 인덱스 디버그 유틸
  benchmark/
    FullScanBenchmark.java
    GeoHashBenchmark.java
    HilbertBenchmark.java
    BenchmarkRunner.java
  util/
    GeoUtils.java           Haversine 거리 계산
```

---

## 기술 스택

| 항목 | 내용 |
|------|------|
| **언어** | Java 21 |
| **스토리지** | RandomAccessFile (페이지 기반) |
| **외부 의존성** | 없음 (Framework 없이 순수 Java) |
| **테스트** | JUnit 5 |
| **데이터** | 79,081건 한국 병원 데이터 |
| **시각화** | Python (folium, matplotlib) |

---

## 로드맵

<details>
<summary>전체 Phase 펼치기</summary>

```
✅ Phase 1: Storage (Page, DiskManager, CacheManager)
✅ Phase 2: API (RecordManager, PageLayout)
✅ Phase 3: GeoHash (GeoHash, GeoHashIndex, SpatialRecordManager)
✅ Phase 4: Benchmark (Full Scan vs GeoHash vs Hilbert)
✅ Phase 5: Hilbert Multi-Interval Query + Seek Count 비교
✅ Phase 6: DiskManager sparse 매핑 테이블
✅ Phase 7: Morton 코드 직접 pageId 매핑 (pageId 분산 187개)
✅ Phase 8: 실제 병원 데이터 연동 + A/B 벤치마크 (50회 평균)
✅ Phase 9: SpatialCacheService (JVM 캐시) + 3종 시나리오 벤치마크
    - Map<pageId, List<HospitalData>> Lazy 캐시
    - pageId 전체 저장 + MBR 필터링으로 누락/초과 방지
    - Random / Mixed / Hotspot 100회 시나리오 측정
    - Mixed 24.6x / Hotspot 46.8x 개선 확인
✅ Phase 10: 캐시 운영 고도화
    - CachePolicy (TTL / maxSize), CacheEntry (만료시각 래퍼)
    - SpatialCacheEngine 리팩토링 (SpatialRecordManager 의존성 제거)
    - SpatialRecordManager 최상단 API 통합 (search / putCache / rebuild)
    - atomic rename 기반 무중단 rebuild
    - SpatialCache<T> 인터페이스 제거 (단순화)
✅ Phase 11: 동시성 이슈 해결 (Bug 1~4)
    - ByteBuffer position() → 절대 위치 메서드 교체 (BufferUnderflowException 제거)
    - CacheManager.getPage() → computeIfAbsent (Page 객체 중복 생성 방지)
    - writeWithOverflow() / readAllCodesFromChain() → ReentrantReadWriteLock 읽기/쓰기 분리 (누락 방지)
    - overflowFreeList → ConcurrentLinkedDeque (중복 pageId 할당 방지)
    - 스레드 500 동시 요청 검증: 동시성으로 인한 누락 0건 확인
✅ Phase 12: GeoHash 경계값 오버플로우 수정
    - getPageIds() maxLatBits/maxLngBits → Math.min((1L<<15)-1, ...) 클램핑
    - 극좌표 근처 좌표 검색 시 페이지 누락 방지
✅ Phase 13: 하위 계층 동시성 이슈 해결 (Bug 5~9)
    - DiskManager.readPage() / writePage() → synchronized (RandomAccessFile race 해결)
    - Page.dirty → volatile (스레드 간 visibility 보장)
    - CacheManager.flush() → synchronized(page) (dirty flag race 해결)
    - PageCacheStore ConcurrentHashMap → LinkedHashMap(access-order) + 전 메서드 synchronized (LRU eviction)
    - DiskManager.rebuild() 실패 시 임시 파일 삭제 + 기존 파일 재오픈 (서비스 연속성 보장)
    - 스레드 500 동시 요청 검증: 데이터 누락 0건 확인
✅ Phase 14: 엔진 메트릭 시스템 구현
    - EngineMetrics — AtomicLong 카운터 + Supplier 실시간 조회
    - MetricsSnapshot — 특정 시점 불변 값 객체
    - 계층별 카운터 주입 (DiskManager / CacheManager / PageCacheStore / SpatialRecordManager)
    - SpatialCacheEngine.getMetrics() — 최상단 메트릭 조회 API
    - GeoIndexMetricsExporter — Micrometer Gauge 등록 → Prometheus / Grafana 연동
    - overflowPageUsed 메트릭 추가 (핫스팟으로 인한 overflow 페이지 사용량 모니터링)
✅ Phase 15: 캐시 워밍업 구현
    - WarmupStore — pageId별 접근 횟수 추적 (ConcurrentHashMap + AtomicLong)
    - persist() / load() — 히트 카운트 디스크 영속 (재시작 후 히스토리 복원)
    - getTopPageIds(n) — 히트 횟수 내림차순 Top N 반환
    - PageCacheStore 연동 — getOrMiss() 시 recordAccess() 호출
    - SpatialCacheEngine.getWarmupCandidates() / getWarmupTargets() / persistWarmup() — Spring 연동 API
    - Spring @PostConstruct 비동기 워밍업 / @PreDestroy persist 흐름 설계
    - IN 쿼리 청크(1000건) 분할로 DB 연결 타임아웃 방지
✅ Phase 16: usedPageCount 메트릭 추가
    - DiskManager.getUsedPageCount() — pageMap.size() 기반 실제 사용 pageId 수
    - CacheManager / SpatialRecordManager 경유 노출
    - MetricsSnapshot.usedPageCount 필드 추가
✅ Phase 17: Batch Loading Cache + Thundering Herd 방지
    - search(batchLoader) — 모든 MISS codes 수집 → 단 1회 DB 배치 조회
    - pendingLoads ConcurrentHashMap — 동일 pageId 중복 로딩 방지 (winner/waiter 구조)
    - putIfAbsent + double-check — race condition 방지 (Bug 10 해결)
    - Spring SpatialCacheService 연동 — 배치 로더 람다 전달 방식으로 단순화
    - JMeter 50 스레드 벤치마크: 개별 Load 대비 약 2.3x 응답속도 개선 (3,400ms → 1,490ms)
⬜ Phase 18: 약국 데이터 색인 연동
    - 약국 코드 + 좌표 → pharmacy.db 색인 (병원과 동일 구조)
    - SpatialCacheEngine<PharmacyDto> 인스턴스 별도 생성 (pageId 공간 자연 분리)
    - Spring SpatialCacheService 약국 엔진 빈 등록 + batchLoader 연동
```

</details>

---

## 라이센스

MIT