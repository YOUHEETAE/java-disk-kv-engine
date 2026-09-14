# Buffer 모듈

Write-Back 전략을 사용하는 메모리 내 페이지 캐싱 계층

---

## 클래스

### CacheManager.java

API 계층과 디스크 사이의 페이지 캐시 관리

**책임:**
- 메모리에 페이지 캐싱 (현재 무제한)
- 같은 pageId 에 Page 객체가 하나뿐임을 보장 — 상위 계층의 락이 이 위에 서 있다
- Write-Back 캐싱 (디스크 쓰기 지연)
- Dirty 페이지 일괄 플러시

**주요 메서드:**
```java
Page findPage(int pageId)                      // 캐시 → 파일. 없으면 null, 캐시에 넣지 않음 — 읽기 경로
Page getOrCreatePage(int pageId)               // 캐시 → 파일 → 새로 생성 — 쓰기 경로 (rebuild 의 put)
void flush()                                   // dirty 페이지를 pageId 순으로 디스크에 쓰기
void rebuild(CacheManagerLoader loader)        // 임시 CacheManager 구축 → atomic rename → 버퍼 초기화
void clearCache()                              // 버퍼 초기화 (캐시만, 디스크 변경 없음)
void close()                                   // 플러시 후 디스크 닫기
int  getDirtyPageCount()                       // 메트릭 — 전체 순회
int  getUsedPageCount()                        // 메트릭 — DiskManager 위임
```

**읽기와 쓰기 경로가 나뉜 이유:**
```
findPage (읽기):
  캐시 확인 → Hit? 반환
            → Miss? 파일 읽기 → 있으면 캐시에 저장 후 반환
                              → 없으면 null. 캐시에 넣지 않는다

getOrCreatePage (쓰기):
  캐시 확인 → 파일 확인 → 둘 다 없으면 new Page(pageId) 를 만들어 캐시에 저장
```

검색이 훑는 pageId 는 저장된 데이터가 아니라 **반경을 덮는 격자**에서 나온다. 그래서 조회 한 번에
"아무도 쓴 적 없는 칸"이 대량으로 섞이는데, 예전 `getPage` 는 그 칸마다 빈 Page 를 만들어 캐시에
눌러앉혔다. 회수 경로는 rebuild 뿐이라 조회 범위만큼 힙이 자랐다. 읽기 경로를 분리해 **없는 페이지는
캐시하지 않는다**.

`getOrCreatePage` 가 앞의 두 단계를 건너뛰고 항상 새로 만들면 안 된다. 이미 레코드가 든 페이지를
덮으면 `initializePage` 가 recordCount 를 0 으로 되돌리고 flush 가 그대로 파일에 써서 기존 레코드가
예외 없이 사라진다.

**flush 가 pageId 순으로 쓰는 이유:**
```
flush:
  cache.values() 를 pageId 오름차순으로 정렬
  각 dirty 페이지마다: 디스크에 쓰기 → dirty 플래그 제거
```

pageId 가 Morton 코드라 오름차순이 곧 Z-곡선 순서다. `savePage` 는 처음 보는 pageId 에만 offset 을
이어 붙이므로, 전부 새 페이지인 rebuild 에서 파일 배치가 공간 인접성을 따라간다. 해시 순서로 쓰면
인덱스가 만들어낸 인접성이 파일에서 사라져, 반경 쿼리가 파일 전체에 흩어진 seek 이 된다.
overflow 페이지는 별도 번호 공간이라 이 정렬로 지역성이 해결되지 않는다.

---

## Thread-safety

**같은 pageId = 같은 Page 객체.** `findPage`·`getOrCreatePage` 둘 다 `ConcurrentHashMap.computeIfAbsent`
라서 같은 key 에 대해 매핑 함수가 한 번만 실행되고, 모든 스레드가 같은 참조를 받는다. 상위 계층
(`SpatialRecordManager`)의 pageId 단위 락은 이 보장 위에서만 의미가 있다 — 락이 지키는 대상이 객체
하나여야 하기 때문이다.

**flush 는 락을 잡지 않는다.** 예전엔 `synchronized (page)` 가 있었지만, 쓰기 경로가 잡는 것은
`SpatialRecordManager` 의 `pageLocks` (RWLock) 라 별개 객체였다. 상호 배제는 같은 락 객체를 잡을 때만
성립하므로 아무것도 막지 못했고, 락처럼 보여 검토를 통과시키는 쪽이 더 해로워 지웠다.

지금 안전한 근거는 락이 아니라 **호출 규약**이다 — flush 는 `close()` 와 `rebuild()` 에서만 불리고,
그 시점에 put 은 돌지 않는다. 런타임 쓰기를 열면 이 규약이 깨지므로 그때는 `pageLocks` 의 키를
primary 에서 각 pageId 로 내려 flush 가 같은 락에 참여해야 한다.

`clearCache()` 는 dirty 페이지를 그냥 버린다. rebuild 경로에서는 임시 쪽을 이미 flush 했으므로
안전하지만, 공개 메서드로서는 "호출 전 flush" 라는 암묵 계약이 있다.

---

## Phase 11: getPage() computeIfAbsent 교체

### 왜 수정했는가?

기존 `getPage()` 는 check-then-act 패턴으로 구현되어 있었다. 두 연산 사이에 원자성이 없어 동시 접근 시
같은 pageId 에 대해 서로 다른 `Page` 객체가 생성됐다.

```
스레드 A: cache.get(42) → null
스레드 B: cache.get(42) → null          ← 동시에 null 확인
스레드 A: loadPage(42)  → Page@0xAAA
스레드 B: loadPage(42)  → Page@0xBBB    ← 다른 객체 생성
스레드 A: cache.put(42, Page@0xAAA)
스레드 B: cache.put(42, Page@0xBBB)     ← 덮어씀

결과:
  스레드 A → Page@0xAAA 보유
  캐시     → Page@0xBBB
  → 스레드 A의 write는 캐시에 반영 안 됨 → 데이터 유실
```

### 해결

`computeIfAbsent` 로 check-then-act 를 단 하나의 원자 연산으로 통합했다.

```java
// Before
Page page = cache.get(pageId);
if (page == null) {
    page = diskManager.loadPage(pageId);
    cache.put(pageId, page);
}
return page;

// After (현재의 findPage)
return cache.computeIfAbsent(pageId, diskManager::loadPage);
```

`computeIfAbsent` 는 같은 key 에 대해 mapping function 을 단 한 번만 실행한다. 모든 스레드가 동일한
`Page` 객체 참조를 공유하게 된다. 덤으로 매핑 함수가 null 을 반환하면 저장하지 않고 null 을 돌려주므로,
"없으면 캐시에 넣지 않는다"가 별도 분기 없이 성립한다.

이 수정은 `SpatialRecordManager` 의 `ReentrantReadWriteLock` 과 반드시 함께 동작해야 한다. 같은 pageId
= 같은 Page 객체가 보장되어야 락이 올바르게 동작하기 때문이다.

> 자세한 내용은 [CONCURRENCY.md](../../../../../CONCURRENCY.md) Bug 2 참고

---

## 핵심 개념

### Write-Back vs Write-Through

| 전략 | 동작 | 성능 |
|------|------|------|
| Write-Through | 즉시 디스크에 쓰기 | 쓰기마다 디스크 I/O |
| Write-Back | 메모리에만 쓰고 나중에 플러시 | 일괄 디스크 I/O, 정렬 가능 |

**현재 구현:**
- Write-Back 활성화
- 수동 플러시 (`flush()` 또는 `close()` 호출) — 배경 flush 스레드 없음
- 크기 제한 없음 (무제한 캐시)
- Eviction 정책 없음 — 회수는 rebuild 뿐

서비스 중 쓰기가 없어(색인은 rebuild 로만) 성립하는 구조다. 런타임 쓰기를 열면 배경 flush 와 flush 락
참여가 함께 재검토 대상이 된다.

### rebuild() — 임시 CacheManager로 구축 후 교체

```
왜 필요한가:
  기존 버퍼를 먼저 비우면 구축 중 요청이 빈 상태로 서비스됨 ❌
  → 임시 CacheManager에 완전히 구축 후 atomic rename → 버퍼 초기화

흐름:
  1. diskManager.rebuild(tempDm → {
       임시 CacheManager(tempDm) 생성
       loader로 임시 파일에 데이터 구축      ← 이때 getOrCreatePage 가 쓰인다
       tempCm.flush() → pageId 순으로 임시 파일 기록
     })
  2. atomic rename 완료 (기존 파일 교체됨)
  3. cache.clear() → 버퍼 초기화 → 새 파일 기반으로 전환
```

---

## 의존성

- `geoindex.storage.DiskManager` — 디스크 연산 (`loadPage` · `savePage` · `rebuild`)
- `geoindex.storage.Page` — 페이지 객체
- `java.util.concurrent.ConcurrentHashMap` — 유일성 보장의 근거
