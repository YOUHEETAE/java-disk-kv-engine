# Index 모듈

공간 인덱스 구현 - GeoHash (Morton 코드) / Hilbert Multi-Interval Query

---

## 인터페이스

```java
public interface SpatialIndex {
    int toPageId(double lat, double lng);                          // 삽입 시
    List<Integer> getPageIds(double lat, double lng, double radiusKm); // 검색 시
}
```

---

## GeoHash

### GeoHash.java

| 메서드 | 역할 |
|--------|------|
| `toMorton(lat, lng, precision)` | 좌표 → Morton 코드 (Z-curve 비트 인터리빙) |
| `deinterleave(morton)` | Morton → lngBits / latBits 분리 |
| `interleave(lngBits, latBits)` | lngBits / latBits → Morton 재조합 |
| `neighbor(morton, dLat, dLng)` | 인접 셀 계산 |
| `getNeighbors(morton)` | 8방향 + 중심 셀 목록 반환 |

**Morton 코드란?**

```
위도/경도 비트를 번갈아 인터리빙 → 2차원 공간을 1차원으로 매핑
가까운 좌표 = 가까운 Morton 값 (Z-curve 공간 근접성 보존)

lng: 101  lat: 110
→ 인터리빙: 110110 (lng 홀수 bit, lat 짝수 bit)
```

---

### GeoHashIndex.java - 설계 개선 과정

#### 1차: steps × steps 고정 셀

```
PRECISION = 6 → 격자 1.2km
3×3 고정 셀 → 반경 3.6km만 커버

문제: 반경 5km 요청 시 경계 누락
후보 70건, 실제 27건 중 23건 누락
```

#### 2차: 동적 steps (steps × steps 가변)

```
steps = ceil(radiusKm / 1.2) + 1 = 6
→ 13×13 = 169개 셀

문제: pageId 매핑 방식
  toLong(geohash) % MAX_PAGES
  → % 연산으로 공간 지역성 파괴
  → 인접 셀이 전혀 다른 pageId에 배치됨
```

#### 3차: Morton SHIFT 방식

```
Morton 코드 상위 비트를 pageId로 사용
toPageId = morton >> SHIFT

문제: 한국 좌표가 Morton 공간의 97% 지점에 위치
  SHIFT=10 → max pageId=948,627 → DB 3,705MB
  SHIFT=20 → 전체가 1~2개 pageId로 뭉침

  Z-curve 특성상 반경 5km = Morton 공간에서 77밖에 안 됨
  어떤 SHIFT 값으로도 충분한 분산 불가
```

#### 4차: Morton 직접 pageId + sparse 매핑 테이블 (현재)

```
Morton 값 자체를 pageId로 사용
DiskManager sparse 매핑: HashMap<pageId, 파일오프셋>
→ pageId가 6천만이어도 실제 파일 = 데이터 페이지 수 × 4KB

getPageIds():
  네 꼭짓점 → deinterleave → lngBits/latBits 범위 추출
  → 격자 순회 → Morton 재조합 → pageId 수집

결과: 반경 5km = 187개 pageId로 분산 (이전 1~2개 → 187개) ✅
```

**현재 구현:**

```java
// toPageId: Morton 직접 사용
public int toPageId(double lat, double lng) {
    return (int) GeoHash.toMorton(lat, lng, PRECISION);
}

// getPageIds: 네 꼭짓점 → 격자 범위 → 전체 순회
public List<Integer> getPageIds(double lat, double lng, double radiusKm) {
    // MBR 네 꼭짓점 Morton 계산
    // deinterleave → minLngBits~maxLngBits, minLatBits~maxLatBits
    // 격자 순회 → interleave → (int)morton = pageId
}
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

% MAX_PAGE       → 힐버트 연속성 파괴 → Full Scan 退化
/ RANGE_PER_PAGE → 선형 분할 → 연속성 보존
```

**getPageIds (검색):**

```
① 중심 격자 (cx, cy) 계산
② 사각형 MBR 격자 순회 (stepsY × stepsX)
③ encodeGrid(x, y) → 힐버트값 → pageId visited 마킹
④ visited[10000] 배열 순회 → Interval Merge
⑤ disjoint interval → pageId 목록 반환
```

**왜 Multi-Interval인가?**

```
원형 영역은 힐버트 곡선 위에서 하나의 연속 구간이 아님
→ 사분면 경계에서 힐버트값이 크게 점프
→ 여러 disjoint interval로 쪼개짐

강남 반경 5km 실측:
  [3766], [3772~3773], [3775], [3879~3884], [3889~3890]
  → 5개 interval, pageId 13개

선형 범위: pageId 275개 (엉뚱한 지역 포함)
GeoHash:   pageId 187개
Hilbert:   pageId  13개 ← 힐버트의 진짜 강점
```

---

## 방식별 비교

| 항목 | GeoHash | Hilbert |
|------|---------|---------|
| **pageId 수** | 187개 | 13개 |
| **후보 수** | 1,366건 | 103건 |
| **검색 시간** | 0ms | 33~37ms |
| **getPageIds 연산** | 187번 | 671만 번 |
| **interval 수** | 분산 | 5개 |
| **Seek Count** | 720 | 124 |

**트레이드오프:**

```
GeoHash:  연산 적음 → 빠름 / pageId 많음 → I/O 많음
Hilbert:  연산 많음 → 느림 / pageId 적음 → I/O 최소

SSD 소규모 환경:  GeoHash 유리 (0ms vs 33ms)
HDD 대용량 환경:  Hilbert 유리 (pageId 13개 × 순차 I/O)
```

---

## 파일 구조

```
index/
  SpatialIndex.java             인터페이스
  GeoHash.java                  Morton 코드 계산 로직
  GeoHashIndex.java             Morton 직접 pageId 매핑
  HilbertCurve.java             힐버트 곡선 계산 로직
  HilbertIndex.java             Multi-Interval Query 구현
  HILBERT_IMPLEMENTATION.md     힐버트 구현 과정 상세 기록
  GEOHASH_IMPLEMENTATION.md     GeoHash 설계 개선 과정 상세 기록
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
