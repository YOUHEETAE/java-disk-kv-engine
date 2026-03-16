# Buffer 모듈

Write-Back 전략을 사용하는 메모리 내 페이지 캐싱 계층

---

## 클래스

### CacheManager.java

API 계층과 디스크 사이의 페이지 캐시 관리

**책임:**
- 메모리에 페이지 캐싱 (현재 무제한)
- Write-Back 캐싱 (디스크 쓰기 지연)
- Dirty 페이지 일괄 플러시

**주요 메서드:**
```java
Page getPage(int pageId)                       // 캐시 또는 디스크에서 가져오기
void putPage(Page page)                        // Dirty 마킹, 디스크에 즉시 쓰지 않음
void flush()                                   // 모든 dirty 페이지 디스크에 쓰기
void rebuild(CacheManagerLoader loader)        // 임시 CacheManager 구축 → atomic rename → 버퍼 초기화
void clearCache()                              // 버퍼 초기화 (캐시만, 디스크 변경 없음)
void close()                                   // 플러시 후 디스크 닫기
```

**캐시 전략:**
```
getPage:
  캐시 확인 → Hit? 반환
            → Miss? 디스크 읽기, 캐시에 저장, 반환

putPage:
  Dirty 마킹
  캐시에 저장
  디스크에 즉시 쓰지 않음

flush:
  각 dirty 페이지마다:
    디스크에 쓰기
    Dirty 플래그 제거
```

---

## Thread-safety

`flush()`의 isDirty() 체크 → writePage() → clearDirty() 세 연산을 `synchronized(page)` 블록으로 원자적 보호. flush 도중 다른 스레드의 markDirty()로 인한 변경사항 유실 방지.
→ [CONCURRENCY.md Bug 8 참고](../../../../../CONCURRENCY.md)

---

## Phase 12: getPage() computeIfAbsent 교체

### 왜 수정했는가?

기존 `getPage()`는 check-then-act 패턴으로 구현되어 있었다. 두 연산 사이에 원자성이 없어 동시 접근 시 같은 pageId에 대해 서로 다른 `Page` 객체가 생성됐다.

```
스레드 A: cache.get(42) → null
스레드 B: cache.get(42) → null          ← 동시에 null 확인
스레드 A: readPage(42)  → Page@0xAAA
스레드 B: readPage(42)  → Page@0xBBB    ← 다른 객체 생성
스레드 A: cache.put(42, Page@0xAAA)
스레드 B: cache.put(42, Page@0xBBB)     ← 덮어씀

결과:
  스레드 A → Page@0xAAA 보유
  캐시     → Page@0xBBB
  → 스레드 A의 write는 캐시에 반영 안 됨 → 데이터 유실
```

### 해결

`computeIfAbsent`로 check-then-act를 단 하나의 원자 연산으로 통합했다.

```java
// Before
Page page = cache.get(pageId);
if (page == null) {
    page = diskManager.readPage(pageId);
    cache.put(pageId, page);
}
return page;

// After
return cache.computeIfAbsent(pageId, diskManager::readPage);
```

`computeIfAbsent`는 같은 key에 대해 mapping function을 단 한 번만 실행한다. 모든 스레드가 동일한 `Page` 객체 참조를 공유하게 된다.

이 수정은 `SpatialRecordManager`의 `synchronized(page)`와 반드시 함께 동작해야 한다. 같은 pageId = 같은 Page 객체가 보장되어야 `synchronized(page)`가 올바른 락으로 동작하기 때문이다.

> 자세한 내용은 [CONCURRENCY.md](../../../../../CONCURRENCY.md) Bug 2 참고

---

## 핵심 개념

### Write-Back vs Write-Through

| 전략 | 동작 | 성능 |
|------|------|------|
| Write-Through | 즉시 디스크에 쓰기 | 느림 (쓰기마다 디스크 I/O) |
| Write-Back | 메모리에만 쓰고 나중에 플러시 | 빠름 (일괄 디스크 I/O) |

**현재 구현:**
- Write-Back 활성화
- 수동 플러시 (flush() 또는 close() 호출)
- 크기 제한 없음 (무제한 캐시)
- Eviction 정책 없음

### rebuild() — 임시 CacheManager로 구축 후 교체

```
왜 필요한가:
  기존 버퍼를 먼저 비우면 구축 중 요청이 빈 상태로 서비스됨 ❌
  → 임시 CacheManager에 완전히 구축 후 atomic rename → 버퍼 초기화

흐름:
  1. diskManager.rebuild(tempDm → {
       임시 CacheManager(tempDm) 생성
       loader로 임시 파일에 데이터 구축
       tempCm.flush() → 임시 파일 기록
     })
  2. atomic rename 완료 (기존 파일 교체됨)
  3. cache.clear() → 버퍼 초기화 → 새 파일 기반으로 전환
```

---

## 성능 영향

### Write-Through (이전)
```
100 쓰기 → 100 디스크 I/O → 500ms
```

### Write-Back (현재)
```
100 쓰기 → 100 메모리 쓰기 → 50ms
1 플러시 → 1 일괄 디스크 I/O → 50ms
총: 100ms
```

---

## 의존성

- `geoindex.storage.DiskManager` — 디스크 연산
- `geoindex.storage.Page` — 페이지 객체
- `java.util.concurrent.ConcurrentHashMap` — thread-safe 캐시 저장소