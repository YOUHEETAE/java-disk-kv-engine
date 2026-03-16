# Storage 모듈

페이지 기반 디스크 I/O 계층

---

## 클래스

### Page.java

메모리 내 4KB 페이지

**구조:**
```
고정 크기: 4096 bytes
dirty 플래그: Write-Back 캐싱 여부
```

**주요 메서드:**
```java
int getPageId()    // 페이지 번호 반환
byte[] getData()   // 원시 바이트 배열 반환
boolean isDirty()  // 수정 여부 확인
void markDirty()   // 수정됨으로 표시
void clearDirty()  // dirty 플래그 제거
```

---

### PageLayout.java

Page 내부 슬롯 구조 정의 및 레코드 읽기/쓰기

**페이지 헤더 (16 bytes):**
```
0-3:   recordCount
4-7:   freeSpaceStart
8-11:  magic (0xCAFEBABE)
12-15: overflowPageId
```

**슬롯 디렉토리 (8 bytes/slot):**
```
[offset (4)][length (4)]
```

**레코드 형식:**
```
[valueLength (4)][value]
```

---

## Thread-safety

`readPage()`, `writePage()` 모두 `synchronized`로 보호됨. `RandomAccessFile` 공유 파일 포인터의 seek/read race condition 해결.
`Page.dirty`는 `volatile` 선언으로 스레드 간 가시성 보장.
→ [CONCURRENCY.md Bug 5, 6, 7 참고](../../../../../CONCURRENCY.md)

---

## Phase 12: ByteBuffer 절대 위치 읽기/쓰기

### 왜 수정했는가?

`ByteBuffer`는 내부 `position` 상태를 가진다. 여러 스레드가 같은 `Page` 객체를 공유할 때 `position()`은 thread-safe하지 않다.

```
스레드 A: buffer.position(120)   ← 포지션 설정
스레드 B: buffer.position(4088)  ← 덮어씀
스레드 A: buffer.getInt()        ← 엉뚱한 위치 읽기 → BufferUnderflowException
```

### 해결

`position()` 호출을 완전히 제거하고 절대 위치 메서드로 교체했다.

```java
// Before — position 공유 (thread-unsafe)
buffer.position(offset);
int valueLength = buffer.getInt();
buffer.get(value);

// After — 절대 위치 (thread-safe)
int valueLength = buffer.getInt(offset);
System.arraycopy(buffer.array(), offset + 4, value, 0, valueLength);
```

`buffer.getInt(index)`는 내부 position을 변경하지 않는다. 각 스레드가 독립적인 오프셋으로 접근하므로 충돌이 없다.

> 자세한 내용은 [CONCURRENCY.md](../../../../../CONCURRENCY.md) Bug 1 참고

---

### DiskManager.java

물리적 디스크 I/O + sparse 매핑 테이블

---

## 핵심 설계: sparse 매핑 테이블

### 왜 필요한가?

GeoHash Index는 Morton 코드를 직접 pageId로 사용합니다.

```
강남 병원 → Morton 코드 → pageId = 60,712,140
```

초기 설계(순차 저장)였다면:

```
offset = pageId × 4096
= 60,712,140 × 4096
= 248,659,005,440 bytes ≈ 234GB
```

pageId가 6천만이어도 파일이 234GB가 됩니다. 실제 데이터는 수십 MB에 불과한데도.

**해결:** pageId → 파일 오프셋 매핑 테이블을 헤더에 저장합니다.

```
실제 파일 크기 = 헤더(매핑 테이블) + 데이터 페이지 수 × 4KB
```

---

## 파일 구조

```
[0 ~ 3]              entryCount (int)
[4 ~ 4 + MAX*12]     매핑 테이블: pageId(4) + offset(8) × MAX_ENTRIES
[DATA_OFFSET ~ ...]  실제 페이지 데이터 (순차 추가)
```

상수:
```java
MAX_ENTRIES  = 100_000   // 최대 pageId 수
ENTRY_SIZE   = 12        // pageId(4) + offset(8)
DATA_OFFSET  = 4 + 100_000 × 12 = 1,200,004 bytes ≈ 1.2MB
```

---

## 동작 방식

### writePage (새 페이지)

```
1. pageMap에 pageId 없음 → 새 페이지
2. offset = nextDataOffset (파일 끝)
3. 헤더에 (pageId, offset) 엔트리 추가
4. entryCount 갱신
5. 데이터 기록
```

헤더 엔트리는 새 페이지일 때만 추가합니다.
기존 페이지 업데이트는 데이터만 덮어씁니다 → O(1) 고정 비용.

### writePage (기존 페이지)

```
1. pageMap에 pageId 있음 → offset 조회
2. 해당 offset에 데이터 덮어쓰기
3. 헤더 변경 없음
```

### readPage

```
1. pageMap에서 pageId → offset 조회
2. offset 없으면 빈 페이지 반환 (new Page(pageId))
3. 있으면 해당 위치에서 4KB 읽기
```

### 재시작 복구

```
DiskManager 생성 시 loadPageMap() 호출
→ 헤더 읽기 → pageMap / entryIndex 복원
→ 크래시 후에도 매핑 복원 가능
```

---

## rebuild() — atomic rename 기반 파일 교체

### 왜 필요한가?

```
단순 삭제 + 재생성:
  파일 삭제 → 재구축 중 readPage() 요청 → 깨진 파일 읽음 ❌

atomic rename:
  임시 파일(.new)에 완전히 구축 → rename으로 교체
  rename 전까지 기존 파일 살아있음 → 요청 중단 없음 ✅
```

### 흐름

```
1. 임시 DiskManager(filePath + ".new") 생성
2. loader로 임시 파일에 데이터 구축
3. 임시 파일 닫기
4. 기존 파일 닫기
5. Files.move(ATOMIC_MOVE + REPLACE_EXISTING)
6. 새 파일 열기 + 내부 상태(pageMap, entryIndex) 교체
```

---

## 트레이드오프

| 항목 | 순차 저장 (이전) | sparse 매핑 (현재) |
|------|------------|---------------|
| 파일 크기 | pageId × 4KB (234GB 가능) | 데이터 페이지 수 × 4KB |
| readPage | seek(pageId × 4096) | 헤더 조회 → seek(offset) |
| writePage (새) | seek(pageId × 4096) | 헤더 추가 + 데이터 기록 |
| writePage (기존) | seek(pageId × 4096) | pageMap 조회 + 데이터 기록 |
| 재시작 | 바로 가능 | loadPageMap() 필요 |
| 헤더 크기 | 없음 | 고정 1.2MB |

Morton 직접 pageId를 사용하려면 sparse 매핑이 필수입니다.