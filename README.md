# MiniDB - GeoSpatial Index Engine

위치 기반 병원 검색 시스템의 공간 인덱스 성능 문제를 해결하기 위해 만든 경량 공간 인덱스 엔진

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

결론: 7만 건 규모에서 Full Scan + BETWEEN이 최적
→ 평균 응답 시간 30-50ms
```

### 문제 2: Redis Geohash 캐싱의 한계

```
좌표 기반 캐싱 → 10m 떨어진 사용자도 캐시 미스
Geohash 격자 캐싱 도입 → 캐시 HIT 29-124ms

하지만:
→ Redis 외부 인프라 의존
→ 네트워크 레이턴시 포함
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

## 목표

```
Full Scan 125ms → 목표 10ms 이하
Redis 없이 공간 클러스터링만으로 달성
```

---

## 아키텍처

```
┌─────────────────────────────────┐
│  API Layer (RecordManager)       │  Key-Value 인터페이스
│  API Layer (SpatialRecordManager)│  공간 인덱스 인터페이스
├─────────────────────────────────┤
│  Index Layer (SpatialIndex)      │  GeoHash / 힐버트 커브
├─────────────────────────────────┤
│  Buffer Layer (CacheManager)     │  Write-Back 캐싱
├─────────────────────────────────┤
│  Storage Layer (DiskManager)     │  페이지 기반 디스크 I/O
│  Storage Layer (PageLayout)      │  슬롯 페이지 구조
└─────────────────────────────────┘
```

### 핵심 설계

**공간 클러스터링**
```
삽입 시: 좌표 → GeoHash → PageId 결정
→ 가까운 병원이 자동으로 같은/인접 페이지에 저장

검색 시: 반경 내 GeoHash 셀 → PageId 목록 → 해당 페이지만 읽기
→ Full Scan 없이 필요한 페이지만 I/O
```

**원형 필터링 외부 위임**
```
이 엔진: 사각형(MBR) 기준 페이지 목록 반환
애플리케이션: Haversine 공식으로 원형 필터링
→ 엔진은 I/O 최소화에만 집중
```

---

## 성능 결과

| 방식 | 검색 시간 | 스캔 범위 | 결과 |
|------|----------|----------|------|
| **Full Scan** | 125ms | 79,081건 전체 | 27건 |
| **GeoHash** | 8ms | 1,379건 (후보) | 27건 |
| **힐버트** | - | - | - |

**GeoHash: Full Scan 대비 15배 향상 (125ms → 8ms)**

---

## 구현 방식

### GeoHash 인덱스

```
좌표 → Geohash 문자열 (precision 6, 격자 1.2km)
→ Base32 → long 변환 (공간 근접성 보존)
→ long % MAX_PAGES → PageId

hashCode() 사용 시 공간 근접성 파괴
Base32 → long 변환으로 접두사가 같으면 long 값도 가까움
```

**동적 셀 범위**
```
steps = ceil(radiusKm / 1.2) + 1
radiusKm=5.0 → steps=6 → 13×13=169개 셀

3×3 고정 시 → 반경 5km 커버 불가 (누락 발생)
동적 확장 → 정확도 100% 달성
```

### 힐버트 커브 인덱스 (예정)

```
GeoHash 대비 공간 연속성 더 우수
→ 힐버트 값이 가까우면 실제 거리도 가까움
→ GeoHash와 동일한 SpatialIndex 인터페이스로 교체 가능
```

---

## 모듈 구조

```
minidb/
  storage/
    Page.java           4KB 페이지
    DiskManager.java    디스크 I/O
    PageLayout.java     슬롯 페이지 구조
  buffer/
    CacheManager.java   Write-Back 캐싱
  api/
    RecordManager.java          Key-Value 저장
    SpatialRecordManager.java   공간 인덱스 저장/검색
  index/
    SpatialIndex.java   인터페이스
    GeoHash.java        순수 계산 로직
    GeoHashIndex.java   SpatialIndex 구현체
  benchmark/
    FullScanBenchmark.java    Full Scan 측정
    GeoHashBenchmark.java     GeoHash 측정
  util/
    GeoUtils.java       Haversine 거리 계산
```

---

## 사용법

```java
DiskManager diskManager = new DiskManager("data.db");
CacheManager cacheManager = new CacheManager(diskManager);
SpatialRecordManager manager = new SpatialRecordManager(cacheManager, new GeoHashIndex());

// 삽입
manager.put(37.4979, 127.0276, hospital.toBytes());

// 반경 검색 (사각형 MBR)
List<byte[]> candidates = manager.searchRadius(37.4979, 127.0276, 5.0);

// 원형 필터링은 애플리케이션에서
candidates.stream()
    .map(b -> Hospital.fromBytes("", b))
    .filter(h -> GeoUtils.haversine(lat, lng, h.coordinateY, h.coordinateX) <= 5.0)
    .collect(Collectors.toList());

cacheManager.close();
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
⬜ Phase 5: 힐버트 커브 구현
⬜ Phase 6: 최종 3방향 비교
⬜ Phase 7: 교차 계산 알고리즘 (후보 수 최적화)
```

---

## 라이센스

MIT - 학습 목적
