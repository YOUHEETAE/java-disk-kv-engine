# MiniDB 프로젝트 로드맵

## 프로젝트 목표

힐버트 커브 기반 공간 클러스터링 DB 엔진 구현

---

## 완성된 인프라 (✅)

```
minidb/
  storage/
    Page.java          ✅ 4KB 페이지
    DiskManager.java   ✅ 디스크 I/O
  buffer/
    CacheManager.java  ✅ Write-Back 캐싱
  api/
    RecordManager.java ✅ Key-Value, Overflow Chaining
  test/                ✅ 전체 테스트 통과
```

---

## 로드맵

### Phase 1: 공간 인덱스 인터페이스 설계

**목표:** Geohash와 힐버트가 공유할 추상화 레이어

```
SpatialIndex (interface)
  toPageId(lat, lng)               → 좌표를 PageId로 변환
  getPageIds(lat, lng, radiusKm)   → 반경 내 PageId 목록 반환
```

**산출물**
- `index/SpatialIndex.java`

---

### Phase 2: Geohash 구현

**목표:** 기준선(Baseline) 성능 측정용

```
GeoHash.java
  encode(lat, lng, precision)  → "wydm9"
  decode(hash)                 → (lat, lng)
  getNeighbors(hash)           → 인접 8개 격자

GeoHashIndex.java (implements SpatialIndex)
  toPageId()                   → Geohash → PageId
  getPageIds()                 → 반경 내 인접 격자 → PageId 목록
```

**산출물**
- `index/GeoHash.java`
- `index/GeoHashIndex.java`

---

### Phase 3: SpatialRecordManager 구현

**목표:** 공간 인덱스 기반 삽입/검색 엔진

```
SpatialRecordManager.java
  put(lat, lng, value)                     → 힐버트/Geohash PageId에 저장
  batchInsert(List<Hospital>)              → 정렬 후 순차 삽입
  searchRadius(lat, lng, radiusKm)         → 반경 검색
```

**핵심 설계**
```java
// SpatialIndex 주입으로 Geohash/힐버트 교체 가능
SpatialRecordManager manager =
    new SpatialRecordManager(cacheManager, new GeoHashIndex());
```

**산출물**
- `api/SpatialRecordManager.java`

---

### Phase 4: Benchmark (Geohash 기준)

**목표:** Full Scan vs Geohash 성능 측정

```
측정 항목:
  삽입 성능: 79,081건 배치 삽입 시간
  검색 성능: 반경 5km 검색 응답 시간

예상 결과:
  Full Scan:       ~50ms
  Geohash HashMap: ~15ms
```

**산출물**
- `benchmark/SpatialBenchmark.java`
- 성능 측정 결과 문서

---

### Phase 5: 힐버트 커브 구현

**목표:** Geohash 대비 성능 개선

```
HilbertCurve.java
  encode(lat, lng)   → 힐버트 값 (long)
  decode(hilbert)    → (lat, lng)

HilbertIndex.java (implements SpatialIndex)
  toPageId()         → 힐버트 값 → PageId
  getPageIds()       → 반경 내 힐버트 PageId 목록
```

**힐버트의 핵심 장점**
```
삽입 시: 가까운 병원 → 인접한 PageId → 순차 I/O
검색 시: 반경 내 격자 → 연속된 PageId → 순차 읽기
```

**배치 삽입 최적화**
```
79,081건 → 힐버트 값 기준 정렬 → 순차 기록
→ 디스크 랜덤 I/O 최소화
```

**산출물**
- `index/HilbertCurve.java`
- `index/HilbertIndex.java`

---

### Phase 6: Benchmark (최종)

**목표:** 3방향 성능 비교로 힐버트 우위 증명

```
Full Scan           ~50ms   ←  병원 프로젝트 현재
Geohash HashMap     ~15ms   ←  일반적인 방식
힐버트 클러스터링    ~5ms    ←  MiniDB 핵심
```

**측정 항목**
```
삽입 성능: 배치 삽입 I/O 횟수, 시간
검색 성능: 반경 검색 응답 시간
캐시 효과: 캐시 HIT 시 응답 시간
```

**산출물**
- 최종 벤치마크 결과
- 학술제 발표 자료 기초 데이터

---

### Phase 7: 원형 교차 격자 계산 (고도화)

**목표:** 불필요한 I/O 제거

```
현재:
  반경 내 격자 9개 고정 조회

개선:
  완전 포함 격자 → 전체 반환
  걸치는 격자    → 거리 계산 후 필터
  제외 격자      → 조회 자체를 안 함
```

**산출물**
- `index/CircleGridIntersection.java`
- SpatialIndex.getPageIds() 정확도 개선

---

### Phase 8: B-Tree 인덱스 (선택)

**조건:** Phase 6 벤치마크 후 HashMap 성능이 부족할 경우

```
현재 HashMap 인덱스:
  힐버트 값 → RecordId

B-Tree 인덱스로 교체:
  범위 검색 최적화
  대용량 데이터 대응
```

---

## 디렉토리 구조 (완성 후)

```
minidb/
  storage/
    Page.java              ✅
    DiskManager.java       ✅
  buffer/
    CacheManager.java      ✅
  api/
    RecordManager.java     ✅
    SpatialRecordManager.java  ⏳ Phase 3
  index/
    SpatialIndex.java          ⏳ Phase 1
    GeoHash.java               ⏳ Phase 2
    GeoHashIndex.java          ⏳ Phase 2
    HilbertCurve.java          ⏳ Phase 5
    HilbertIndex.java          ⏳ Phase 5
    CircleGridIntersection.java ⏳ Phase 7
  benchmark/
    SpatialBenchmark.java      ⏳ Phase 4, 6
  test/
    DiskManagerTest.java   ✅
    CacheManagerTest.java  ✅
    RecordManagerTest.java ✅
    GeoHashTest.java           ⏳ Phase 2
    HilbertTest.java           ⏳ Phase 5
    SpatialBenchmarkTest.java  ⏳ Phase 4, 6
```

---

## 핵심 성과 요약

| 항목 | 내용 |
|------|------|
| **핵심 기술** | 힐버트 커브 기반 공간 페이지 클러스터링 |
| **차별점** | 삽입 시점부터 공간 클러스터링 → 별도 인덱스 불필요 |
| **재사용성** | SpatialIndex 인터페이스로 Geohash/힐버트 교체 가능 |
| **성능 목표** | Full Scan 50ms → 힐버트 5ms |
| **발표 스토리** | Full Scan → Geohash → 힐버트 단계적 개선 증명 |
