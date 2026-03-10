package geoindex.cache;

import java.time.Instant;
import java.util.List;

/**
 * pageId 단위 캐시 값 객체 — 데이터 + 만료 시간
 *
 * Why:
 *   ConcurrentHashMap의 value로 List<T>를 직접 쓰면
 *   TTL 만료 여부를 확인할 방법이 없다.
 *   → 값과 만료 시간을 함께 감싸는 래퍼 객체로 분리
 *
 *   expiresAt == null → TTL_DISABLE → 항상 유효
 */
public class CacheEntry<T> {

    private final List<T> data;
    private final Instant expiresAt; // null = 만료 없음

    private CacheEntry(List<T> data, Instant expiresAt) {
        this.data = data;
        this.expiresAt = expiresAt;
    }

    // TTL 없음
    public static <T> CacheEntry<T> of(List<T> data) {
        return new CacheEntry<>(data, null);
    }

    // TTL 있음 — 생성 시점 + ttl = 만료 시점
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