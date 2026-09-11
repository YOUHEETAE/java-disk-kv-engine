package geoindex.cache;

import java.time.Instant;
import java.util.List;

/**
 * pageId 단위 캐시 값 객체 — 데이터 + 만료 시각.
 *
 * 래퍼가 필요한 이유:
 *   맵의 value 를 List<T> 로 두면 만료 시각을 붙일 자리가 없다. 값 옆에 시각을 함께
 *   넣어야 getOrMiss 가 꺼내는 자리에서 바로 판정할 수 있다.
 *
 * expiresAt == null 이면 TTL_DISABLE — 만료하지 않는다. 기본 정책이 이쪽이다.
 * data 는 PageCacheStore.put 이 List.copyOf 로 넣으므로 불변이다.
 */
public class CacheEntry<T> {

    private final List<T> data;
    private final Instant expiresAt;

    private CacheEntry(List<T> data, Instant expiresAt) {
        this.data = data;
        this.expiresAt = expiresAt;
    }

    public static <T> CacheEntry<T> of(List<T> data) {
        return new CacheEntry<>(data, null);
    }

    public static <T> CacheEntry<T> of(List<T> data, Instant expiresAt) {
        return new CacheEntry<>(data, expiresAt);
    }

    public List<T> getData() {
        return data;
    }

    /**
     * 만료 여부
     * expiresAt == null → TTL_DISABLE → 항상 false (만료 안됨)
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}