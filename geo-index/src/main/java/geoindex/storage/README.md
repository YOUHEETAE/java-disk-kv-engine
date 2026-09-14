# Storage 모듈

페이지 기반 디스크 I/O 계층

---

## 클래스

### Page.java

메모리 내 4KB 페이지

**구조:**
```
고정 크기: 4096 bytes
dirty 플래그: Write-Back 캐싱 여부 — volatile (CacheManager.getDirtyPageCount 가 락 없이 읽는다)
```

**주요 메서드:**
```java
int getPageId()      // 페이지 번호 반환
byte[] getData()     // 원시 바이트 배열 반환
ByteBuffer buffer()  // 같은 배열을 감싼 뷰 — 절대 위치 접근용 (아래 Phase 11)
boolean isDirty()    // 수정 여부 확인
void markDirty()     // 수정됨으로 표시
void clearDirty()    // dirty 플래그 제거
```

---

### PageLayout.java

Page 내부 슬롯 구조 정의 및 레코드 읽기/쓰기

**페이지 헤더 (16 bytes):**
```
0-3:   recordCount
4-7:   freeSpaceStart
8-11:  magic (0xCAFEBABE) — 초기화 여부. 파일에서 읽은 페이지가 이 값이 아니면 손상
12-15: overflowPageId — 다음 overflow 페이지, 없으면 -1
```

**슬롯 디렉토리 (8 bytes/slot):**
```
[offset (4)][length (4)]
```

**레코드 형식:**
```
[valueLength (4)][value]
```

슬롯은 앞에서 뒤로, 레코드는 뒤에서 앞으로 자란다. 빈 공간이 항상 가운데 한 덩어리라 "들어가나"
판정이 뺄셈 한 번이다. 레코드에 key 는 없다 — pageId 자체가 "어느 격자냐"라는 키라서 페이지 안에서
다시 비교할 이유가 없고, 읽기는 슬롯 순회로 전량 반환한다.

**계약:**
```java
MAX_RECORD_SIZE = 4096 - 16 - 8 - 4 = 4,068   // 한 페이지에 담을 수 있는 최대 value
int writeRecord(Page, byte[])                   // 꽉 차면 -1. 상위 계층이 overflow 를 붙이는 신호
```

`MAX_RECORD_SIZE` 보다 큰 value 는 빈 페이지에서도 -1 이 나와 상위 계층이 overflow 를 무한히 할당한다.
그래서 `SpatialRecordManager.put` 진입점에서 크기를 막는다. 레코드는 바이트만 저장하며, 문자열 ↔
바이트 변환과 인코딩(UTF-8)은 api 계층이 정한다.

---

## Thread-safety

`loadPage()`, `savePage()` 모두 `synchronized`. `RandomAccessFile` 의 공유 파일 포인터 때문에 seek 과
read/write 가 한 덩어리여야 하고, `savePage` 는 `nextDataOffset` · `entryCount` · `pageMap` 을 함께 갱신하므로
나뉘면 두 스레드가 같은 오프셋을 받는다. `rebuild()` 의 파일 교체 구간도 같은 모니터를 잡는다 (아래).
`Page.dirty` 는 `volatile` 로 스레드 간 가시성 보장.
→ [CONCURRENCY.md Bug 5, 6, 7 참고](../../../../../CONCURRENCY.md)

---

## Phase 11: ByteBuffer 절대 위치 읽기/쓰기

### 왜 수정했는가?

`ByteBuffer` 는 내부 `position` 상태를 가진다. 여러 스레드가 같은 `Page` 객체를 공유할 때 `position()` 은
thread-safe 하지 않다.

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

`buffer.getInt(index)` 는 내부 position 을 변경하지 않는다. 각 스레드가 독립적인 오프셋으로 접근하므로
충돌이 없다. 이 안전성은 락이 아니라 "공유 상태를 쓰지 않는다"에서 온다 — 보기 편하다고 position 방식으로
되돌리면 조용히 깨진다.

> 자세한 내용은 [CONCURRENCY.md](../../../../../CONCURRENCY.md) Bug 1 참고

---

### DiskManager.java

물리적 디스크 I/O + sparse 매핑 테이블

---

## 핵심 설계: sparse 매핑 테이블

### 왜 필요한가?

GeoHash Index 는 Morton 코드를 직접 pageId 로 사용합니다.

```
강남 병원 → Morton 코드 → pageId = 60,712,140
```

초기 설계(순차 저장)였다면:

```
offset = pageId × 4096
= 60,712,140 × 4096
= 248,659,005,440 bytes ≈ 234GB
```

pageId 가 6천만이어도 파일이 234GB 가 됩니다. 실제 데이터는 수십 MB 에 불과한데도.

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
MAX_ENTRIES  = 100_000   // 최대 pageId 수. 넘으면 savePage 가 IllegalStateException
ENTRY_SIZE   = 12        // pageId(4) + offset(8)
DATA_OFFSET  = 4 + 100_000 × 12 = 1,200,004 bytes ≈ 1.2MB
```

매핑 테이블을 파일 앞에 고정 크기로 잡은 대가로 페이지 수 상한이 생겼지만, 데이터 시작 위치가 컴파일
타임 상수가 되어 코드가 단순하다. 실사용이 상한에 가까워지면 테이블을 파일 끝(footer)으로 옮기는 편이
낫다.

---

## 동작 방식

### savePage (새 페이지)

```
1. pageMap 에 pageId 없음 → 새 페이지
2. offset = nextDataOffset (파일 끝)
3. 헤더에 (pageId, offset) 엔트리 추가
4. entryCount 갱신
5. 데이터 기록
```

헤더 엔트리는 새 페이지일 때만 추가합니다.
기존 페이지 업데이트는 데이터만 덮어씁니다 → O(1) 고정 비용.

### savePage (기존 페이지)

```
1. pageMap 에 pageId 있음 → offset 조회
2. 해당 offset 에 데이터 덮어쓰기
3. 헤더 변경 없음
```

### loadPage

```
1. pageMap 에서 pageId → offset 조회
2. offset 없으면 null — 파일에 없는 페이지를 만들지 않는다
3. 있으면 해당 위치에서 4KB 읽기
```

null 을 돌려주는 것은 정상 경로다. 검색이 훑는 pageId 는 반경을 덮는 격자에서 나오므로 대부분 파일에
없다. 빈 Page 를 만들어 돌려주면 그것이 상위 캐시에 눌러앉아 조회 범위만큼 힙이 자란다 (buffer 모듈
`findPage` 참고). 새 페이지가 필요한 쓰기 경로는 `CacheManager.getOrCreatePage` 가 처리한다.

### 재시작 복구

```
DiskManager 생성 시 loadMappingTable() 호출
→ 헤더 읽기 → pageMap · entryCount · nextDataOffset 복원
→ 크래시 후에도 매핑 복원 가능
```

---

## rebuild() — atomic rename 기반 파일 교체

### 왜 필요한가?

```
단순 삭제 + 재생성:
  파일 삭제 → 재구축 중 loadPage() 요청 → 깨진 파일 읽음 ❌

atomic rename:
  임시 파일(.new)에 완전히 구축 → rename 으로 교체
  rename 전까지 기존 파일 살아있음 → 요청 중단 없음 ✅
```

### 흐름

```
0. 이전 실행이 남긴 .new 가 있으면 삭제 (프로세스가 죽어 finally 조차 못 돈 경우의 잔재)
1. 임시 DiskManager(filePath + ".new") 생성
2. loader 로 임시 파일에 데이터 구축                ← 오래 걸린다. 락 밖
3. 임시 파일 닫기
── synchronized (this) — loadPage / savePage 와 같은 모니터 ──
4. 기존 파일 닫기
5. Files.move(ATOMIC_MOVE + REPLACE_EXISTING)
6. 새 파일 열기 + 내부 상태 교체 — pageMap · entryCount · nextDataOffset 을 tempDm 에서 복사
```

교체 구간을 잠그는 이유: 이 안에는 dbFile 이 닫혀 있고 pageMap 이 비어 있는 순간이 있다. 잠그지 않으면
요청 스레드가 닫힌 파일을 읽어 예외가 나거나, 빈 pageMap 을 보고 "그런 페이지 없음"으로 답한다 — 예외
없는 조용한 누락이다. 오래 걸리는 적재(2)는 일부러 밖에 두어 무중단을 유지한다.

6 에서 파일을 다시 읽지 않는 이유: 새 파일의 매핑은 tempDm 이 적재하며 이미 채웠다. 파일에서 다시 읽으면
엔트리 수만큼 seek 이 락 안에서 일어나고, 도중에 실패하면 pageMap 이 반쯤 찬 채로 남는다. 메모리 복사는
실패하지 않는다.

### 실패 처리

정리는 `catch` 가 아니라 `finally` 에 있다. loader 가 던지는 예외는 대부분 unchecked 라 `catch(IOException)`
으로 잡히지 않는데, 그러면 임시 파일과 핸들이 남고 열린 핸들 때문에 삭제까지 실패한다.

```
어느 단계든 예외 → finally:
  임시 DiskManager 닫기 → 임시 파일 삭제 → (4 이후였다면) 기존 파일 재오픈 → 서비스 계속

단계 4(기존 파일 닫기) 이후 실패:
  기존 파일은 디스크에 존재하지만 닫힌 상태
  → 재오픈. 이것까지 실패하면 재시작이 필요하다
```

닫기 → 삭제 순서는 Windows 때문이다. 열려 있는 파일을 지우지 못하고, 열린 파일을 이동할 수도 없어서
기존 파일을 먼저 닫은 뒤 move 한다.

---

## 트레이드오프

| 항목 | 순차 저장 (이전) | sparse 매핑 (현재) |
|------|------------|---------------|
| 파일 크기 | pageId × 4KB (234GB 가능) | 데이터 페이지 수 × 4KB |
| loadPage | seek(pageId × 4096) | 헤더 조회 → seek(offset) |
| savePage (새) | seek(pageId × 4096) | 헤더 추가 + 데이터 기록 |
| savePage (기존) | seek(pageId × 4096) | pageMap 조회 + 데이터 기록 |
| 재시작 | 바로 가능 | loadMappingTable() 필요 |
| 헤더 크기 | 없음 | 고정 1.2MB |

Morton 직접 pageId 를 사용하려면 sparse 매핑이 필수입니다.
