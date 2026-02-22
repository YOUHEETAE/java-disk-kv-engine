# Storage 모듈

디스크 기반 페이지 저장 계층

## 클래스

### Page.java

메모리 내 4KB 페이지를 나타냄

**구조:**
- 고정 크기: 4096 bytes
- 헤더, 슬롯, 레코드 포함
- Write-Back 캐싱을 위한 dirty 플래그

**주요 메서드:**
```java
int getPageId()          // 페이지 번호 반환
byte[] getData()         // 원시 바이트 배열 반환
ByteBuffer buffer()      // ByteBuffer 래퍼 반환
boolean isDirty()        // 수정 여부 확인
void markDirty()         // 수정됨으로 표시
void clearDirty()        // Dirty 플래그 제거
```

**사용 예:**
```java
Page page = new Page(0);
page.markDirty();
byte[] data = page.getData();
```

### DiskManager.java

물리적 디스크 I/O 연산 처리

**책임:**
- 디스크에서 페이지 읽기
- 디스크에 페이지 쓰기
- 파일 핸들 관리

**주요 메서드:**
```java
Page readPage(int pageId)    // 디스크에서 읽기
void writePage(Page page)    // 디스크에 쓰기
void close()                 // 파일 핸들 닫기
```

**파일 레이아웃:**
```
Offset = pageId × 4096

Page 0: bytes 0-4095
Page 1: bytes 4096-8191
Page 2: bytes 8192-12287
...
```

**사용 예:**
```java
DiskManager dm = new DiskManager("data.db");

// 읽기
Page page = dm.readPage(5);

// 쓰기
dm.writePage(page);

// 닫기
dm.close();
```

## 핵심 개념

### 페이지 기반 저장

- 디스크 I/O는 고정 크기 청크 단위로 동작
- 디스크 연산 횟수 감소
- OS 페이지 크기와 정렬

### 왜 4KB인가?

- 일반적인 OS 페이지 크기
- 오버헤드와 활용도의 균형
- 업계 표준 (MySQL, PostgreSQL은 8KB-16KB 사용)

## 트레이드오프

| 측면 | 선택 | 이유 |
|------|------|------|
| 페이지 크기 | 4KB | 단순, OS 정렬 |
| 파일 형식 | 순차 페이지 | 쉬운 랜덤 접근 |
| 버퍼링 | 없음 (여기서는) | CacheManager에서 처리 |

## 의존성

- java.io.RandomAccessFile - 파일 I/O
- java.nio.ByteBuffer - 메모리 관리