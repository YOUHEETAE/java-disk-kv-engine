Benchmark 모듈
공간 인덱스 성능 측정 - Full Scan vs Geohash vs 힐버트 커브 비교

목표
병원 위치 데이터 반경 5km 검색 성능을 3가지 방식으로 비교
Full Scan        → 기준선
Geohash          → 일반적인 공간 인덱스
힐버트 클러스터링 → MiniDB 핵심 최적화

테스트 환경
항목내용데이터 규모79,081건 (실제 병원 데이터 규모)검색 조건강남 좌표 기준 반경 5km검색 좌표lat: 37.4979, lng: 127.0276페이지 크기4KB캐시 전략Write-Back (검색 전 캐시 초기화)

더미 데이터 구조
실제 hospital_main 테이블 구조 기반
필드타입비고hospital_codeStringH00001 ~ H79081coordinate_xdouble경도 (126.0 ~ 129.5)coordinate_ydouble위도 (33.0 ~ 38.5)doctor_numString랜덤hospital_addressString랜덤hospital_nameString랜덤hospital_telString랜덤district_nameString랜덤hospital_homepageString랜덤province_nameString랜덤
직렬화 방식: 바이너리 (ByteBuffer)
[coordinateX: 8byte][coordinateY: 8byte]
[length: 4byte][field: n byte] × 7개 문자열 필드

성능 측정 결과
Phase 4: Full Scan (기준선)
항목결과삽입 시간198ms (79,081건)검색 시간172ms검색 결과37건스캔 방식전체 순회 (getAllValues)캐시 영향캐시 초기화 후 측정 (순수 디스크 I/O)
Phase 4: Geohash (예정)
항목결과삽입 시간-검색 시간-검색 결과-
Phase 6: 힐버트 클러스터링 (예정)
항목결과삽입 시간-검색 시간-검색 결과-

파일 구조
benchmark/
  Hospital.java           병원 데이터 클래스 (toBytes/fromBytes)
  DummyDataGenerator.java 79,081건 더미 데이터 생성
  FullScanBenchmark.java  Full Scan 성능 측정

실행 방법
FullScanBenchmark.main() 실행
→ 더미 데이터 생성
→ RecordManager에 삽입
→ 캐시 초기화 (flush + clearCache)
→ Full Scan 검색
→ 결과 출력
→ DB 파일 자동 삭제

핵심 교훈
캐시 초기화가 중요
캐시 초기화 전: 96ms  (메모리에서 읽음 → 왜곡된 수치)
캐시 초기화 후: 172ms (디스크에서 읽음 → 실제 성능)
공정한 벤치마크를 위해 검색 전 반드시 flush() + clearCache() 필요