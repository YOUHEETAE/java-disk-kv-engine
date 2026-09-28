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
long getPageId()     // 페이지 번호 반환
byte[] getData()     // 원시 바이트 배열 반환
ByteBuffer buffer()  // 같은 배열을 감싼 뷰 — 절대 위치 접근용 (아래 Phase 11)
boolean isDirty()    // 수정 여부 확인
void markDirty()     // 수정됨으로 표시
void clearDirty()    // dirty 플래그 제거
```

`PAGE_SIZE` 는 파일에 적히지 않는다. 바꾸면 `DiskManager.FORMAT_VERSION` 도 올려야 한다 — 옛 파일을
새 코드로 열면 페이지 경계가 어긋난 자리를 읽는다.

---

### PageLayout.java

Page 내부 슬롯 구조 정의 및 레코드 읽기/쓰기

**페이지 헤더 (16 bytes):**
```
0-3:   recordCount
4-7:   freeSpaceStart
8-11:  magic (0xCAFEBABE) — 초기화 여부. 파일에서 읽은 페이지가 이 값이 아니면 손상
12-15: hasOverflow — 다음 칸이 있는지 여부만. 번호는 저장하지 않는다
```

번호를 저장하지 않는 이유는 계산되기 때문이다. pageId 의 상위 비트가 격자 셀, 하위 10비트가 체인 순번이라
"다음 칸 = 현재 + 1" 이 성립한다 (api 모듈 `SpatialRecordManager` 참고).

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
read/write 가 한 덩어리여야 하고, `savePage` 는 `nextDataOffset` · `pageMap` · `footerOnDisk` 를 함께
갱신하므로 나뉘면 두 스레드가 같은 오프셋을 받는다. `rebuild()` 의 파일 교체 구간도 같은 모니터를 잡는다
(아래). `Page.dirty` 는 `volatile` 로 스레드 간 가시성 보장.
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
= 248,676,925,440 bytes ≈ 232GiB
```

pageId 가 6천만이어도 파일이 232GiB 가 됩니다. 실제 데이터는 수십 MB 에 불과한데도.

**해결:** 위치를 계산하지 않고 조회합니다. pageId → 파일 오프셋 매핑을 파일에 함께 저장하고,
기동할 때 메모리로 올립니다.

```
실제 파일 크기 = 헤더 + 데이터 페이지 수 × 4KB + 색인(페이지 수 × 16B) + 트레일러
```

---

## 파일 구조

```
[0 ~ 3]        매직 "MDB1"
[4 ~ 7]        포맷 버전
[8 ~ 15]       예약
[16 ~ ]        페이지 데이터 — 4KB 씩 이어 붙인다
[footer]       (pageId 8B + offset 8B) × 페이지 수, pageId 오름차순
[끝-12 ~ 끝-5] 페이지 수 (long)
[끝-4 ~ 끝-1]  매직 "MDB1"
```

상수:
```java
HEADER_SIZE  = 16   // 매직(4) + 버전(4) + 예약(8)
ENTRY_SIZE   = 16   // pageId(8) + offset(8)
TRAILER_SIZE = 12   // 페이지 수(8) + 매직(4)
```

페이지 수에 상한이 없습니다. 완성된 파일 크기는 `16 + n×4096 + n×16 + 12` 로 페이지 수 n 에만
비례합니다 (페이지 0장이면 28바이트).

헤더에서 실제로 쓰는 것은 앞 8바이트뿐이고 뒤 8은 비워 둡니다. 지금 잡아 두는 이유는, 나중에 필드를
더하려면 데이터 시작 위치가 밀리고 그러면 이미 쓴 파일 전부가 못 읽는 파일이 되기 때문입니다.

### 색인을 파일 끝에 두는 이유

앞에 두려면 데이터를 쓰기 전에 자리를 예약해야 하고, **예약한 크기가 그대로 페이지 수 상한**이 됩니다.
끝에 두면 데이터를 다 쓴 뒤에 크기가 정해지므로 상한이 없습니다. 대신 읽는 쪽이 색인의 시작 위치를
모르게 되어, 파일 맨 끝에 페이지 수를 적어 두고 거기서 거꾸로 찾아갑니다.

쓰기 한 번으로 끝나는 포맷들이 대개 이 모양입니다 — Parquet 는 파일 양끝에 `PAR1` 을 두고 메타데이터를
끝에 싣고, ORC 는 postscript 를, LSM 트리의 SSTable 은 인덱스 블록을 뒤에 둡니다. 반대로 제자리 수정이
잦은 포맷(PostgreSQL · InnoDB · SQLite)은 페이지 안에 구조를 두지 이런 색인을 만들지 않습니다.

### 완성 표식

매직이 **파일의 마지막 4바이트**라는 것이 핵심입니다. 그 자리에 매직이 있다는 것은 그 앞의 색인이
끝까지 쓰였다는 뜻이고, 쓰다가 죽은 파일은 이 표식이 없어 기동 때 거부됩니다. 매직이 `0` 이면 안 되는
이유도 여기 있습니다 — 0 으로 채워진 파일이 검사를 통과해 버립니다.

표식이지 체크섬은 아닙니다. 프로세스 사망은 잡지만 부분 쓰기나 비트 썩음은 잡지 않습니다.

---

## 동작 방식

### savePage (새 페이지)

```
1. pageMap 에 pageId 없음 → 새 페이지
2. 파일 끝에 옛 색인이 있으면 잘라낸다 (배치당 한 번)
3. offset = nextDataOffset (데이터 끝)
4. pageMap 에 기록 — 메모리에만
5. 데이터 기록
```

파일 쓰기는 **페이지 하나**뿐입니다. 색인은 메모리에만 쌓이고, 배치가 끝날 때 `sealIndex()` 가 한 번에
기록합니다.

2단계가 필요한 이유: 새 페이지가 붙는 순간 파일 끝의 색인은 지금 데이터와 짝이 맞지 않습니다.
지우지 않으면 **옛 색인이 새로 쓴 페이지보다 길 때 끝의 매직이 살아남아**, 여기서 죽은 파일이 완성된
것으로 통과하고 엉뚱한 매핑이 복원됩니다. 덮어쓰기(기존 페이지)는 pageId·offset 이 그대로라 색인이
여전히 맞으므로 자르지 않습니다.

### savePage (기존 페이지)

```
1. pageMap 에 pageId 있음 → offset 조회
2. 해당 offset 에 데이터 덮어쓰기
3. 색인 변경 없음
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

### sealIndex — 배치의 끝

`CacheManager.flush()` 가 dirty 페이지를 다 쓴 뒤에 부릅니다. 색인은 "지금 파일에 든 페이지 목록"이라
배치가 끝나는 그 지점에서만 정확하고, **이 호출이 끝나면 파일은 다시 열 수 있는 상태**가 됩니다.

`close()` 에 두지 않은 이유: 이 레포에서 `flush()` 호출 37곳 중 곧바로 `close()` 로 이어지는 것은
`CacheManager.rebuild` 안의 한 곳뿐입니다. 나머지는 flush 뒤에 계속 살아 있으므로, close 에만 두면
그 구간 내내 파일이 미완성으로 남습니다. 무엇보다 warm start 가 절실한 상황은 정상 종료가 아니라
`kill -9` 쪽인데, close 는 그때 불리지 않습니다.

이미 짝이 맞는 색인이 있으면 다시 쓰지 않습니다. 그래서 배치 끝이라고 판단하는 곳마다 부담 없이
부를 수 있고, 검색만 한 flush 에서는 파일 쓰기가 0회입니다.

`savePage` 를 직접 쓰는 호출자(`DiskManagerLoader`)는 스스로 `sealIndex()` 로 끝내야 합니다.
`rebuild` 는 임시 파일에 대해 그것을 대신 해 줍니다 — "옛것 아니면 새것"을 약속한 것이 `rebuild` 이기
때문입니다.

### 재시작 복구

```
DiskManager 생성 시
  파일이 0바이트  → initializeNewFile() : 헤더 + 빈 색인
  파일이 있음      → readFooter()       : 검증 → 페이지 수 → 엔트리 → pageMap 복원
```

한 프로세스가 사는 동안에는 `savePage` 가 `pageMap` 을 채우므로, `readFooter` 는 **기동 시 한 번만**
돕니다. 색인을 파일에 쓰는 이유가 이 복원 하나입니다.

`nextDataOffset` 을 파일 길이가 아니라 **페이지 수**로 계산합니다. 파일 길이에는 색인이 포함되어 있어,
길이를 그대로 쓰면 재시작 뒤 첫 페이지가 옛 색인 뒤에 붙습니다.

페이지가 0장인 파일에도 색인을 써 둡니다. 그래야 "만든 적 있다"와 "만들다 죽었다"가 구별됩니다 —
헤더만 있는 파일은 거부되어야 하고, 페이지 0장인 파일은 조용히 열려야 합니다.

### 검증 — 기동 때 거부하는 네 가지

| 진단 | 메시지 | 읽는 사람이 할 일 |
|------|--------|------------------|
| 길이가 28바이트 미만 | `incomplete index file: … is N bytes` | 재구축 |
| 앞 매직 불일치 | `not an index file: … starts with 0x…` | 경로 확인 — 남의 파일을 열고 있다 |
| 버전 불일치 | `unsupported format version …` | 재구축 |
| 끝 매직 없음 | `incomplete index file: … has no end marker` | 재구축 |

길이 검사를 맨 앞에 두는 이유가 둘입니다. 0바이트(새 파일)와 잘린 파일을 구별해 주고, 뒤따르는 seek 이
음수가 되는 경로를 없앱니다. 그게 없으면 잘린 파일이 `Negative seek offset` 으로 터져 원인이 메시지에
남지 않습니다.

여기서 빈 `pageMap` 으로 조용히 넘어가면 안 됩니다. 데이터가 사라진 것처럼 보이고, 그 상태의 put 이
진짜로 덮어씁니다.

**격자 크기(`BITS_PER_AXIS`)와 페이지 크기는 파일에 적히지 않습니다.** 코드에서 바꾸고 재구축하지
않으면 같은 좌표가 다른 셀 번호가 되어 예외 없이 빈 결과가 납니다. `FORMAT_VERSION` 이 그 자리를 막는
싼 대체물이고, 두 상수 위에 "바꾸면 버전도 올려라" 주석이 있습니다.

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
0. 이전 실행이 남긴 .new 가 있으면 삭제. 못 지우면 던진다
1. 임시 DiskManager(filePath + ".new") 생성
2. loader 로 임시 파일에 데이터 구축                ← 오래 걸린다. 락 밖
3. 임시 파일의 색인 완성 (sealIndex)
4. 임시 파일 닫기
── synchronized (this) — loadPage / savePage 와 같은 모니터 ──
5. 기존 파일 닫기
6. Files.move(ATOMIC_MOVE + REPLACE_EXISTING)
7. 새 파일 열기 + 내부 상태 교체
   — pageMap · nextDataOffset · footerOnDisk 를 tempDm 에서 복사
```

0 단계에서 **던지는** 이유: 잔재를 지우지 못한 채 진행하면 그 파일이 `readFooter` 를 통과할 수 있고
(seal 까지 끝내고 rename 전에 죽은 경우), 그러면 **지난 실행의 페이지가 pageMap 에 실려 새 인덱스에
섞입니다.** 검색이 삭제된 레코드를 돌려주는데 예외는 나지 않습니다. 재구축을 거부하면 라이브 인덱스는
손도 대지 않은 상태로 남으므로, 던지는 쪽이 가볍습니다.

3 단계가 `rebuild` 에 있는 이유: `DiskManagerLoader` 는 `savePage` 만 알면 되고, 배치가 끝났다는 것을
아는 것은 `rebuild` 뿐입니다. `CacheManager` 를 거친 로더라면 그쪽 `flush` 가 이미 색인을 완성했으므로
이 호출은 아무 일도 하지 않습니다.

7 단계가 복사하는 것은 **파일의 상태를 설명하는 필드 전부**입니다. 블록을 나올 때 `this` 의 상태는
`new DiskManager(filePath, …)` 를 새로 만들었을 때와 같아야 합니다. 필드를 추가하면 이 목록에도
한 줄을 더해야 하고, 빠뜨리면 컴파일도 테스트도 통과한 뒤 재시작에서만 틀어집니다.

교체 구간을 잠그는 이유: 이 안에는 dbFile 이 닫혀 있고 pageMap 이 비어 있는 순간이 있다. 잠그지 않으면
요청 스레드가 닫힌 파일을 읽어 예외가 나거나, 빈 pageMap 을 보고 "그런 페이지 없음"으로 답한다 — 예외
없는 조용한 누락이다. 오래 걸리는 적재(2)는 일부러 밖에 두어 무중단을 유지한다.

7 에서 파일을 다시 읽지 않는 이유: 새 파일의 매핑은 tempDm 이 적재하며 이미 채웠다. 파일에서 다시 읽으면
락 구간에 파일 읽기가 들어오고, 도중에 실패하면 pageMap 이 반쯤 찬 채로 남는다. 메모리 복사는 실패하지
않으므로 그 경로 자체를 없앤다.

### 실패 처리

정리는 `catch` 가 아니라 `finally` 에 있다. loader 가 던지는 예외는 대부분 unchecked 라 `catch(IOException)`
으로 잡히지 않는데, 그러면 임시 파일과 핸들이 남고 열린 핸들 때문에 삭제까지 실패한다.

```
어느 단계든 예외 → finally:
  임시 DiskManager 닫기 → 임시 파일 삭제 → (5 이후였다면) 기존 파일 재오픈 → 서비스 계속

단계 5(기존 파일 닫기) 이후 실패:
  기존 파일은 디스크에 존재하지만 닫힌 상태
  → 재오픈. 이것까지 실패하면 재시작이 필요하다
```

`finally` 는 어디서 왔는지 알 수 없으므로 진행도를 플래그에 적어 둔다.

| 플래그 | 언제 true | 무엇을 정하나 |
|--------|-----------|---------------|
| `swapped` | 락 블록 **마지막 문장** | 청소가 필요한가 — true 면 아무것도 하지 않는다 |
| `dbFileClosed` | 5 단계 직후 | 재오픈이 필요한가 |

`swapped` 를 마지막에 두는 덕에 `swapped == true` 가 "교체됐다"가 아니라 **"전부 끝났다"** 를 뜻한다.
`dbFileClosed` 가 필요한 구간은 5 단계와 `swapped = true` 사이뿐이다 — 그 전에 실패했으면 핸들이
멀쩡한데 무조건 재오픈하면 옛 핸들을 흘린다. `RandomAccessFile` 에는 `isClosed()` 가 없어 플래그 말고
방법이 없다.

닫기 → 삭제 순서는 Windows 때문이다. 열려 있는 파일을 지우지 못하고, 열린 파일을 이동할 수도 없어서
기존 파일을 먼저 닫은 뒤 move 한다.

생성자가 검증에 실패할 때도 열어 둔 핸들을 닫는다. 닫지 않으면 Windows 가 그 파일을 지우지 못해,
파일을 버리고 다시 만드는 복구까지 막힌다.

---

## 트레이드오프

| 항목 | 계산 방식 (offset = pageId × 4096) | 조회 방식 (현재) |
|------|------------|---------------|
| 파일 크기 | pageId × 4KB (232GiB 가능) | 페이지 수 × (4KB + 16B) + 28B |
| loadPage | seek(pageId × 4096) | pageMap 조회 → seek(offset) |
| savePage (새) | seek(pageId × 4096) | 데이터 기록 1회. 색인은 배치 끝에 |
| savePage (기존) | seek(pageId × 4096) | pageMap 조회 + 데이터 기록 |
| 재시작 | 바로 가능 | readFooter() 로 매핑 복원 |
| 페이지 수 상한 | 없음 | 없음 |
| 메모리 | 없음 | pageMap — 엔트리당 키·값 + 해시 항목 |

Morton 직접 pageId 를 사용하려면 조회 방식이 필수입니다. 대가는 매핑을 메모리에 들고 있어야 한다는
것인데, 페이지 하나가 4KB 인 것에 비하면 색인 엔트리 16B 는 0.4% 이고 그 대신 파일이 실제 데이터에만
비례합니다.
