# MiniDB 아키텍처 다이어그램

## 레이어 구조

```mermaid
flowchart TB
    subgraph App["애플리케이션"]
        SVC["Spring 서비스\nAbstractSpatialCacheEngine 상속"]
    end

    subgraph Api["API Layer"]
        SCE["SpatialCacheEngine\nJVM 캐시 HIT/MISS · 배치 로딩\nThundering Herd 방지"]
        SRM["SpatialRecordManager\noverflow 체인 생성/순회 · pageId 별 락"]
    end

    subgraph Index["Index Layer"]
        GHI["GeoHashIndex\n좌표 → Morton pageId\n반경 → pageId 목록"]
    end

    subgraph Buffer["Buffer Layer"]
        CM["CacheManager\nWrite-Back · findPage / getOrCreatePage"]
        PCS["PageCacheStore\nLRU + TTL (도메인 객체 캐시)"]
    end

    subgraph Storage["Storage Layer"]
        DM["DiskManager\nloadPage / savePage · pageId → offset 매핑"]
        PL["PageLayout\n4KB 안쪽 주소 체계 (Slotted Page)"]
    end

    DB[("인덱스 파일\n헤더(매핑 테이블) + 4KB 페이지들")]

    SVC -->|search / rebuild| SCE
    SCE -->|pageId 별 코드 목록| SRM
    SCE --> PCS
    SRM -->|toPageId / getPageIds| GHI
    SRM -->|findPage / getOrCreatePage| CM
    SRM -->|레코드 읽기·쓰기| PL
    CM -->|캐시 미스 → loadPage| DM
    CM -->|flush → savePage| DM
    DM --> DB

    style Api fill:#e8f5e9
    style Index fill:#f3e5f5
    style Buffer fill:#e3f2fd
    style Storage fill:#fff3e0
```

캐시가 두 종류다.

| | 담는 것 | 위치 | 정책 |
|---|---|---|---|
| `CacheManager` | 4KB **페이지** | Buffer Layer | evict 없음, rebuild 때만 비움 |
| `PageCacheStore` | 도메인 **객체**(병원) | API Layer | LRU + TTL |

MiniDB 는 병원 **코드**만 돌려준다. 실제 데이터는 서비스가 MariaDB 에서 가져와
`PageCacheStore` 에 담는다. 그래서 인덱스 파일이 손상돼도 원본은 멀쩡하다 —
`CorruptedIndexException` 을 던지고 부분 결과를 반환하지 않는 근거가 이것이다.

---

## 클래스 의존성

```mermaid
classDiagram
    class SpatialCacheEngine~T~ {
        -SpatialRecordManager spatialRecordManager
        -PageCacheStore~T~ pageCacheStore
        -WarmupStore warmupStore
        +search(lat, lng, radiusKm, loader) List~T~
        +rebuild(loader)
    }

    class SpatialRecordManager {
        -CacheManager cacheManager
        -SpatialIndex spatialIndex
        -Map~Integer,ReentrantReadWriteLock~ pageLocks
        -Deque~Integer~ overflowFreeList
        +put(lat, lng, value)
        +searchRadiusCodesByPageId(...) Map
        +rebuild(loader)
    }

    class SpatialIndex {
        <<interface>>
        +toPageId(lat, lng) int
        +getPageIds(lat, lng, radiusKm) List~Integer~
    }

    class GeoHashIndex {
        -int BITS_PER_AXIS = 15
    }

    class CacheManager {
        -ConcurrentHashMap~Integer,Page~ cache
        +findPage(pageId) Page
        +getOrCreatePage(pageId) Page
        +flush()
        +rebuild(loader)
    }

    class DiskManager {
        -RandomAccessFile dbFile
        -Map~Integer,Long~ pageMap
        +loadPage(pageId) Page
        +savePage(Page)
        +rebuild(loader)
    }

    class Page {
        -int pageId
        -byte[] data
        -volatile boolean dirty
    }

    class PageLayout {
        <<utility>>
        +writeRecord(page, value) int
        +readAllRecords(page) List~byte[]~
        +getOverflowPageId(page) int
    }

    SpatialCacheEngine --> SpatialRecordManager
    SpatialRecordManager --> SpatialIndex
    SpatialRecordManager --> CacheManager
    SpatialRecordManager ..> PageLayout : 레코드 해석
    GeoHashIndex ..|> SpatialIndex
    CacheManager --> DiskManager
    CacheManager --> Page : 캐싱
    DiskManager --> Page : 파일 ↔ 메모리
    PageLayout ..> Page : 바이트 해석
```

`SpatialRecordManager` 는 `SpatialCacheEngine` 을 모른다. 의존이 한 방향이다.

---

## 데이터 흐름: put — rebuild 안에서만 실행된다

```mermaid
sequenceDiagram
    participant L as loader (호출자)
    participant SRM as SpatialRecordManager
    participant IDX as GeoHashIndex
    participant CM as CacheManager
    participant PL as PageLayout

    L->>SRM: put(lat, lng, value)
    SRM->>SRM: 크기 검사 — 4,068B 초과면 거부
    SRM->>IDX: toPageId(lat, lng)
    IDX-->>SRM: Morton pageId

    Note over SRM: writeLock(pageId) — 페이지 획득부터 기록까지 한 덩어리

    SRM->>CM: getOrCreatePage(pageId)
    CM-->>SRM: Page (없으면 새로 만든다)

    loop 체인 끝까지
        SRM->>PL: 초기화 안 됐으면 initializePage
        SRM->>PL: writeRecord(page, value)
        alt 슬롯 확보
            PL-->>SRM: slotId
        else 페이지가 꽉 참 (-1)
            SRM->>SRM: allocateOverflowPage()
            SRM->>PL: setOverflowPageId(page, 새 pageId)
            SRM->>CM: getOrCreatePage(새 pageId)
        end
    end

    Note over CM: 디스크에 쓰지 않는다 (Write-Back)
```

`put` 은 `public` 이지만 운영에서 부르는 곳은 `rebuild` 안의 로더 하나뿐이다.
디스크 기록은 마지막 `flush` 에서 pageId 오름차순으로 한 번에 일어난다.

---

## 데이터 흐름: search

```mermaid
sequenceDiagram
    actor U as 사용자
    participant SCE as SpatialCacheEngine
    participant PCS as PageCacheStore
    participant SRM as SpatialRecordManager
    participant IDX as GeoHashIndex
    participant CM as CacheManager
    participant DM as DiskManager

    U->>SCE: search(lat, lng, radiusKm)
    SCE->>SRM: searchRadiusCodesByPageId(...)
    SRM->>IDX: getPageIds(lat, lng, radiusKm)
    IDX-->>SRM: pageId 목록 (오름차순)

    loop pageId 마다
        SRM->>CM: findPage(pageId)
        alt 캐시에도 파일에도 없음
            CM-->>SRM: null — 아무도 쓴 적 없는 칸이라 건너뛴다
        else 있음
            CM->>DM: 캐시 미스면 loadPage
            DM-->>CM: Page
            Note over SRM: readLock(pageId) — 이 락이 체인 전체를 보호
            SRM->>SRM: 체인 순회 → 코드 수집
        end
    end
    SRM-->>SCE: Map(pageId → 코드 목록)

    loop pageId 마다
        SCE->>PCS: 캐시 확인
        alt HIT
            PCS-->>SCE: 도메인 객체
        else MISS
            SCE->>SCE: MISS 코드를 모아 loader 1회 호출 (배치)
            SCE->>PCS: 결과 저장
        end
    end
    SCE-->>U: 도메인 객체 목록
```

조회는 좌표에서 pageId 를 얻지 않는다. `getPageIds` 가 반경을 덮는 격자를
**기하학적으로 열거**하므로 저장된 데이터와 무관한 칸 번호가 섞이고,
그래서 `findPage` 가 `null` 을 돌려주는 것이 정상 경로다.

---

## 파일 배치

```mermaid
block-beta
    columns 1
    A["entryCount (4B)"]
    B["매핑 테이블 — (pageId 4B + offset 8B) × MAX_ENTRIES · 고정 1.2MB"]
    C["페이지 (4KB)"]
    D["페이지 (4KB)"]
    E["… pageId 오름차순 — flush 가 정렬해서 쓴다"]
```

pageId 가 Morton 코드라 `offset = pageId × 4096` 으로 계산하면 파일이 TB 급이 된다.
그래서 위치를 **계산하지 않고 조회**한다. 파일 크기가 실제로 쓴 페이지 수에만 비례하는 대신,
매핑 테이블을 메모리에 들고 있어야 하고 페이지 수 상한(`MAX_ENTRIES`)이 생긴다.

---

## Page 내부 구조

```mermaid
block-beta
    columns 1
    A["헤더 16B — recordCount(4) | freeSpaceStart(4) | magic 0xCAFEBABE(4) | overflowPageId(4)"]
    B["슬롯 0: offset(4) + length(4)"]
    C["슬롯 1: offset(4) + length(4)"]
    D["… 앞에서 뒤로 자란다"]
    E["← 빈 공간 →"]
    F["… 뒤에서 앞으로 자란다"]
    G["레코드 1: valueLength(4) + value"]
    H["레코드 0: valueLength(4) + value"]
```

레코드에 **key 를 저장하지 않는다.** 공간 인덱스에서는 pageId 자체가 "어느 격자냐"라는
키이므로 페이지 안에서 키를 다시 비교할 이유가 없고, 읽기는 슬롯 순회로 전량 반환한다.

슬롯과 레코드가 반대 방향으로 자라는 덕에 빈 공간이 항상 가운데 한 덩어리로 모여,
남은 공간 판정이 뺄셈 한 번으로 끝난다.

---

## 구현 이력

```mermaid
timeline
    title MiniDB 구현 진행
    section 구현
        Phase 0 : Page / DiskManager / CacheManager
                : RecordManager (Overflow Chaining)
        Phase 1 : SpatialIndex 인터페이스
        Phase 2 : GeoHash / GeoHashIndex
        Phase 3 : SpatialRecordManager
        Phase 4 : Benchmark - Full Scan vs GeoHash
        Phase 5 : Hilbert 곡선 비교 (미채택 · 제거)
        Phase 6 : DiskManager sparse 매핑 테이블
        Phase 7 : Morton 직접 pageId 매핑
        Phase 8 : JVM 캐시 · 워밍업 · 메트릭
    section 복기 · 리팩터링
        Round 1 : Layer 0 - rebuild 원자성과 정리 보장
        Round 2 : Layer 1 - 읽기 경로 부작용 제거
        Round 3 : Layer 2 - 이름과 실체 정합 · Hilbert 제거
        Round 4 : Layer 3 - 체인 손상 처리와 계약 명시
    section 예정
        Layer 0 재설계 : long 복합 pageId (primary, 체인 순번)
                      : 정렬 배열 매핑 - MAX_ENTRIES 상한 제거
                      : 격자 크기 재산정
```
