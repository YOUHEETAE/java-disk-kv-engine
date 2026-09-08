# Index 모듈

공간 인덱스 구현 - GeoHash (Morton 코드)

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
| `interleave(lngBits, latBits)` | lngBits / latBits → Morton 재조합 |

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

#### 4차: Morton 직접 pageId + sparse 매핑 테이블

```
Morton 값 자체를 pageId로 사용
DiskManager sparse 매핑: HashMap<pageId, 파일오프셋>
→ pageId가 6천만이어도 실제 파일 = 데이터 페이지 수 × 4KB

getPageIds():
  네 꼭짓점 → deinterleave → lngBits/latBits 범위 추출
  → 격자 순회 → Morton 재조합 → pageId 수집

결과: 반경 5km = 187개 pageId로 분산 (이전 1~2개 → 187개) ✅
```

#### 5차: getPageIds() 경계값 오버플로우 수정 (현재)

```
4차 구현에서 maxLatBits/maxLngBits 계산 시 유효 범위 초과 문제 발견.

latToBits()는 최대 32767 (2^15 - 1)을 반환하지만
maxLatBits = latToBits(maxLat, PRECISION) + 1 → 32768 가능

interleave(32768, ...)는 잘못된 Morton 코드를 생성 → 경계 근처 페이지 누락.
```

**수정:**

```java
// Before
long maxLatBits = latToBits(maxLat, PRECISION) + 1;
long maxLngBits = lngToBits(maxLng, PRECISION) + 1;

// After — 상한 클램핑
long maxLatBits = Math.min((1L << 15) - 1, latToBits(maxLat, PRECISION) + 1);
long maxLngBits = Math.min((1L << 15) - 1, lngToBits(maxLng, PRECISION) + 1);
```

이 버그는 단일 스레드에서도 재현되는 로직 버그다. 극좌표(위도 ±90°, 경도 ±180°) 근처 좌표를 검색할 때 경계 셀의 페이지가 누락된다.

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

// getPageIds: 네 꼭짓점 → 격자 범위 → 전체 순회 (경계값 클램핑 포함)
public List<Integer> getPageIds(double lat, double lng, double radiusKm) {
    // MBR 네 꼭짓점 좌표 계산
    // latToBits/lngToBits → minLngBits~maxLngBits, minLatBits~maxLatBits
    // Math.min((1L<<15)-1, ...) 으로 상한 클램핑
    // 격자 순회 → interleave → (int)morton = pageId
}
```

---

## Hilbert — 고려했으나 채택하지 않음

Z-곡선(Morton)은 격자 경계에서 힐버트값이 크게 점프한다. Hilbert 곡선은 그 점프가
없어 공간 인접성이 1차원에서 더 잘 보존되므로, `HilbertCurve` / `HilbertIndex`를
구현해 비교했다. 결과적으로 채택하지 않고 제거했다(코드는 git 이력에 남아 있다).

**이유 1 — 이점이 나오는 지점까지 가지 않았다.**

Hilbert의 실질 이득은 조회 범위를 연속 구간 몇 개로 병합하는 Multi-Interval
Query에서 나온다. 구현은 "격자를 순회하며 pageId를 표시하고, 표시된 것을 모아
반환"하는 데서 멈췄다. 이름이 코드보다 앞서 있었던 셈이고, 그 상태에서는 반환
형태가 GeoHash와 다르지 않다.

**이유 2 — 비교 조건이 동일하지 않았다.**

측정치의 대부분은 곡선이 아니라 파라미터 차이에서 나왔다.

| | GeoHash | Hilbert |
|---|---|---|
| pageId 결정 | Morton 값 자체 | 힐버트값 / 107,374 |
| 페이지 하나가 덮는 범위 | 격자 한 칸 | 격자 약 10만 칸 |
| 좌표계 | 전지구 | 한국 박스 |

"pageId 13개 vs 187개"는 **페이지 크기가 달랐던 것**이다. 같은 넓이를 굵은
페이지로 덮으면 당연히 개수가 준다. 곡선을 바꾼 효과와 분리되지 않는 수치라
근거로 쓸 수 없다고 판단했다.

**지금의 결론**

현재 규모에서는 파일 전체가 OS 페이지 캐시에 올라가 seek 거리가 응답 시간에
드러나지 않는다. 곡선 선택보다 **격자 크기와 파일 배치 순서**가 먼저 영향을 준다.
후자는 flush를 pageId 순으로 바꿔 이미 처리했고, 전자는 별도 과제로 남아 있다.

---

## 파일 구조

```
index/
  SpatialIndex.java             인터페이스
  GeoHash.java                  Morton 코드 계산 로직
  GeoHashIndex.java             Morton 직접 pageId 매핑
  GEOHASH_IMPLEMENTATION.md     GeoHash 설계 개선 과정 상세 기록
```

---

## 의존 관계

```
GeoHashIndex  → GeoHash
GeoHashIndex  implements SpatialIndex
SpatialRecordManager → SpatialIndex (주입)
```
