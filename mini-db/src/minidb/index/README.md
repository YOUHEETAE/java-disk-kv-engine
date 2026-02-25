# Index 모듈

공간 인덱스 구현 - GeoHash 기반 PageId 클러스터링

---

## 목표

공간적으로 가까운 병원을 같은/인접 페이지에 클러스터링하여 반경 검색 I/O 최소화

```
Full Scan: 79,081건 전체 스캔
GeoHash:  반경 내 셀에 해당하는 페이지만 읽기
```

---

## 인터페이스

### SpatialIndex.java

```java
public interface SpatialIndex {
    int toPageId(double lat, double lng);
    List<Integer> getPageIds(double lat, double lng, double radiusKm);
}
```

| 메서드 | 역할 | 사용 시점 |
|--------|------|----------|
| `toPageId()` | 좌표 → PageId | 삽입 시 |
| `getPageIds()` | 반경 내 PageId 목록 | 검색 시 |

**원형 필터링은 이 엔진의 책임이 아닙니다.** 사용하는 애플리케이션이 담당합니다.

---

## GeoHash

### GeoHash.java

순수 계산 로직 담당

| 메서드 | 역할 |
|--------|------|
| `toGeohash(lat, lng, precision)` | 좌표 → Geohash 문자열 |
| `toLong(geohash)` | Geohash 문자열 → long (Base32 변환) |
| `neighbor(morton, dLat, dLng)` | 인접 셀 계산 |
| `getNeighbors(morton)` | 8방향 + 중심 셀 목록 반환 |

### 핵심: hashCode() 대신 Base32 → long 변환

```
hashCode() 사용 시:
"wydm9xq" → 18374621
"wydm9xr" → -92837462  ← 공간 근접성 파괴
"wydm9xs" → 56473829

Base32 → long 변환 시:
"wydm9xq" → 상위 비트 동일
"wydm9xr" → 상위 비트 동일  ← 공간 근접성 보존
"wydm9xs" → 상위 비트 동일
```

접두사가 같으면 long 값도 가까움 → PageId도 가까움 → 공간 클러스터링 달성

### 경계 처리

```
wrap-around → 서울 옆이 캘리포니아 (잘못된 공간 모델)
clamp       → 경계 셀 중복 반환
INVALID_CELL(-1) → 존재하지 않는 셀 명시적 제거 (채택)
```

### GeoHashIndex.java

SpatialIndex 구현체

| 상수 | 값 | 의미 |
|------|-----|------|
| `PRECISION` | 6 | 격자 크기 1.2km |
| `MAX_PAGE` | 10000 | 최대 페이지 수 |
| `CELL_SIZE_KM` | 1.2 | precision 6 격자 크기 |

### 동적 셀 범위 계산

```
steps = ceil(radiusKm / CELL_SIZE_KM) + 1

radiusKm=5.0 → steps=6 → 13×13=169개 셀
```

**설계 개선 과정:**

```
1차: 3×3 = 9개 셀 고정
→ 커버 범위 3.6km → 반경 5km 커버 불가
→ 27건 중 4건만 검색 (누락 발생)

2차: 동적 셀 범위 확장
→ radiusKm에 따라 steps 계산
→ 27건 완전 일치
```

---

## 파일 구조

```
index/
  SpatialIndex.java    인터페이스
  GeoHash.java         순수 계산 로직
  GeoHashIndex.java    SpatialIndex 구현체
```

---

## 의존 관계

```
GeoHashIndex → GeoHash
GeoHashIndex implements SpatialIndex
SpatialRecordManager → SpatialIndex (주입)
```

---

## 향후 구현

```
HilbertIndex.java
→ SpatialIndex 구현체
→ 힐버트 곡선 기반 PageId 클러스터링
→ GeoHash 대비 공간 연속성 더 우수
```
