# Index 모듈

공간 인덱스 구현 - GeoHash / Hilbert Multi-Interval Query

---

## 인터페이스

```java
public interface SpatialIndex {
    int toPageId(double lat, double lng);       // 삽입 시
    List<Integer> getPageIds(double lat, double lng, double radiusKm); // 검색 시
}
```

---

## GeoHash

### GeoHash.java

| 메서드 | 역할 |
|--------|------|
| `toGeohash(lat, lng, precision)` | 좌표 → Geohash 문자열 |
| `toLong(geohash)` | Geohash → long (Base32 변환, 공간 근접성 보존) |
| `neighbor(morton, dLat, dLng)` | 인접 셀 계산 |
| `getNeighbors(morton)` | 8방향 + 중심 셀 목록 반환 |

**핵심: hashCode() 대신 Base32 → long 변환**

```
hashCode(): "wydm9xq" → 18374621, "wydm9xr" → -92837462  ← 공간 근접성 파괴
Base32→long: 접두사가 같으면 long 값도 가까움  ← 공간 근접성 보존
```

### GeoHashIndex.java

```
PRECISION = 6  → 격자 1.2km
steps = ceil(radiusKm / 1.2) + 1 = 6
→ 13×13 = 169개 셀, 후보 1,379건
```

---

## Hilbert

### HilbertCurve.java

| 메서드 | 역할 |
|--------|------|
| `encode(lat, lng)` | 좌표 → 힐버트값 |
| `encodeGrid(x, y)` | 격자 좌표 → 힐버트값 (getPageIds 내부용) |
| `toGridX(lng)` | 경도 → 격자 x |
| `toGridY(lat)` | 위도 → 격자 y |

```
ORDER = 15 → 32768×32768 격자
힐버트값 범위: 2^30 ≈ 10억
```

### HilbertIndex.java - Multi-Interval Query

**toPageId (삽입):**
```
힐버트값 / RANGE_PER_PAGE → PageId

% MAX_PAGE      → 힐버트 연속성 파괴 → Full Scan 退化
/ RANGE_PER_PAGE → 선형 분할 → 연속성 보존
```

**getPageIds (검색):**
```
① 반경 안 격자(x,y) 순회
② encodeGrid(x, y) → 힐버트값 → PageId 마킹
③ visited[10000] 배열 순회 → Interval Merge
④ disjoint interval → PageId 목록
```

**왜 Multi-Interval인가?**
```
원형 영역은 힐버트 곡선 위에서 여러 disjoint interval로 나뉨

강남 반경 5km 실측:
  [3766], [3772~3773], [3775], [3879~3884], [3889~3890]
  → 5개 interval, PageId 12개

선형 범위: PageId 275개 (엉뚱한 지역 포함)
GeoHash:   PageId 169개
Hilbert:   PageId  12개 ← 힐버트의 진짜 강점
```

---

## 방식별 비교

| 항목 | GeoHash | Hilbert |
|------|---------|---------|
| **PageId 수** | 169개 | 12개 |
| **후보 수** | 1,379건 | 103건 |
| **검색 시간** | 0~16ms | 37ms |
| **getPageIds 연산** | 169번 | 671만 번 |
| **interval 수** | 분산 | 5개 |

**트레이드오프:**
```
GeoHash:  연산 적음 → 빠름, PageId 많음
Hilbert:  연산 많음 → 느림, PageId 적음 (I/O 최소)

HDD 대용량 환경: Hilbert 유리 (PageId 12개 × 순차 I/O)
SSD 소규모 환경: GeoHash 유리 (연산 비용 < I/O 절감)
```

---

## 파일 구조

```
index/
  SpatialIndex.java      인터페이스
  GeoHash.java           순수 계산 로직
  GeoHashIndex.java      SpatialIndex 구현체
  HilbertCurve.java      순수 계산 로직
  HilbertIndex.java      SpatialIndex 구현체 (Multi-Interval)
  HILBERT_IMPLEMENTATION.md  구현 과정 상세 기록
```

---

## 의존 관계

```
GeoHashIndex  → GeoHash
HilbertIndex  → HilbertCurve
GeoHashIndex  implements SpatialIndex
HilbertIndex  implements SpatialIndex
SpatialRecordManager → SpatialIndex (주입)
```
