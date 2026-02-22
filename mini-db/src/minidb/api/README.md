# API 모듈

Key-Value 인터페이스 및 O(1) 인덱스 접근 구현

## 클래스

### RecordManager.java

페이지 저장소 기반 Key-Value API 제공

**주요 기능:**
- Key-Value 연산 (put/get)
- 슬롯 페이지 관리
- Overflow 체인 처리
- 메모리 인덱스 관리

**API:**
```java
void put(String key, byte[] value)
byte[] get(String key)
```

### RecordId.java

레코드의 물리적 위치를 나타내는 값 객체

**구조:**
```java
class RecordId {
    int pageId;
    int slotId;
}
```

**특징:**
- O(1) 직접 접근 지원
- 불변 객체
- equals/hashCode 구현

## 내부 구조

### 페이지 헤더 (16 bytes)
```
0-3:   recordCount
4-7:   freeSpaceStart
8-11:  magic (0xCAFEBABE)
12-15: overflowPageId
```

### 슬롯 디렉토리 (8 bytes/slot)
```
[offset (4)][length (4)]
```

### 레코드 형식
```
[keyLength (4)][key][valueLength (4)][value]
```

## 인덱스

### HashMap 기반 인덱스
```java
Map<String, RecordId> index;
```

페이지 매핑:
```java
pageId = Math.abs(key.hashCode() % 1000)
```

## 연산

### put(key, value)
```
1. pageId 계산 (hash)
2. page 로드
3. slotId = writeRecord()
4. RecordId 생성
5. index 업데이트
```

### get(key)

**기존 (O(n)):**
```
슬롯 순차 스캔 → 키 비교
```

**현재 (O(1)):**
```
1. index에서 RecordId 조회
2. 슬롯 직접 접근
```

## 성능

### 측정 결과 (1000개 레코드)

| 구분 | 기존 | 현재 | 개선 |
|------|------|------|------|
| get() | ~300ms | ~10ms | 30배 |
| 평균 | 0.3ms | 0.01ms | 30배 |

## 제약사항

**구현됨:**
- put/get
- Overflow chain

**미구현:**
- delete
- update 최적화
- 인덱스 영속화

## 의존성

- minidb.buffer.CacheManager
- minidb.storage.Page
- java.nio.ByteBuffer
- java.util.HashMap