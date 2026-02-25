# Benchmark 모듈

공간 인덱스 성능 측정 - Full Scan vs GeoHash vs 힐버트 커브 비교

---

## 목표

병원 위치 데이터 반경 5km 검색 성능을 3가지 방식으로 비교

```
Full Scan        → 기준선
GeoHash          → 공간 인덱스 (격자 기반)
힐버트 클러스터링 → 공간 인덱스 (곡선 기반)
```

---

## 테스트 환경

| 항목 | 내용 |
|------|------|
| **데이터 규모** | 79,081건 (실제 병원 데이터 규모) |
| **검색 조건** | 강남 좌표 기준 반경 5km |
| **검색 좌표** | lat: 37.4979, lng: 127.0276 |
| **페이지 크기** | 4KB |
| **캐시 전략** | Write-Back (검색 전 캐시 초기화) |
| **랜덤 시드** | 42 (동일한 더미 데이터 보장) |

---

## 더미 데이터 구조

실제 `hospital_main` 테이블 구조 기반

| 필드 | 타입 | 비고 |
|------|------|------|
| hospital_code | String | H00001 ~ H79081 |
| coordinate_x | double | 경도 (126.0 ~ 129.5) |
| coordinate_y | double | 위도 (33.0 ~ 38.5) |
| doctor_num | String | 랜덤 |
| hospital_address | String | 랜덤 |
| hospital_name | String | 랜덤 |
| hospital_tel | String | 랜덤 |
| district_name | String | 랜덤 |
| hospital_homepage | String | 랜덤 |
| province_name | String | 랜덤 |

**직렬화 방식:** 바이너리 (ByteBuffer)

---

## 성능 측정 결과

### 최종 비교

| 방식 | 삽입 시간 | 검색 시간 | 후보 수 | 결과 | 개선율 |
|------|----------|----------|--------|------|--------|
| **Full Scan** | 142ms | 125ms | 79,081건 | 27건 | 기준선 |
| **GeoHash** | 158ms | 8ms | 1,379건 | 27건 | **15배** |
| **힐버트** | - | - | - | - | - |

---

## GeoHash 설계 개선 과정

### 1차 시도: 3×3 고정 셀

```
중심 셀 + 주변 8개 = 9개 셀
커버 범위: 3.6km × 3.6km
```

**결과:**
```
후보: 70건
결과: 4건 (27건 중 23건 누락)
```

**문제:** 반경 5km를 3×3 셀(3.6km)로 커버 불가

### 2차 시도: 동적 셀 범위 확장

```
steps = ceil(radiusKm / CELL_SIZE_KM) + 1
      = ceil(5.0 / 1.2) + 1 = 6

범위: -6 ~ +6 = 13×13 = 169개 셀
커버: 15.6km × 15.6km
```

**결과:**
```
후보: 1,379건
결과: 27건 (Full Scan과 일치)
```

---

## 캐시 효과 제거

```
캐시 초기화 전: 메모리에서 읽음 → 왜곡된 수치
캐시 초기화 후: 디스크에서 읽음 → 실제 성능

flush() → clearCache() 순서 필수
```

---

## 파일 구조

```
benchmark/
  Hospital.java              병원 데이터 클래스 (toBytes/fromBytes)
  DummyDataGenerator.java    79,081건 더미 데이터 생성 (SEED=42)
  FullScanBenchmark.java     Full Scan 성능 측정
  GeoHashBenchmark.java      GeoHash 성능 측정
```

---

## 향후 개선 가능성

```
교차 계산 알고리즘
→ 반경과 실제 교차하는 셀만 읽기
→ 후보 1,379건 → 더 줄어들 가능성
→ 검색 시간 8ms → 추가 개선 가능
```
