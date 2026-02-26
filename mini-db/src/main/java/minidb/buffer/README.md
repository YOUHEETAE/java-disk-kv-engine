# Buffer 모듈

Write-Back 전략을 사용하는 메모리 내 페이지 캐싱 계층

## 클래스

### CacheManager.java

API 계층과 디스크 사이의 페이지 캐시 관리

**책임:**
- 메모리에 페이지 캐싱 (현재 무제한)
- Write-Back 캐싱 (디스크 쓰기 지연)
- Dirty 페이지 일괄 플러시

**주요 메서드:**
```java
Page getPage(int pageId)     // 캐시 또는 디스크에서 가져오기
void putPage(Page page)      // Dirty 마킹, 디스크에 즉시 쓰지 않음
void flush()                 // 모든 dirty 페이지 디스크에 쓰기
void close()                 // 플러시 후 디스크 닫기
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

## 향후 개선

### Buffer Pool (계획)
- 캐시 크기 제한 (예: 100 페이지)
- LRU eviction 정책
- Dirty 페이지 eviction 시 디스크 쓰기

**예시:**
```java
// 미래 API
CacheManager cache = new CacheManager(diskManager, 100); // 최대 100 페이지

cache.getPage(1000); // 캐시 가득 차면 LRU 페이지 evict
```

## 의존성

- minidb.storage.DiskManager - 디스크 연산
- minidb.storage.Page - 페이지 객체
- java.util.HashMap - 캐시 저장소