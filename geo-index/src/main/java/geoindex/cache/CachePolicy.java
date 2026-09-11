package geoindex.cache;

import java.time.Duration;

/**
 * 캐시 운영 정책 — 붙이는 쪽이 정하고 엔진은 실행만 한다.
 *
 * TTL 과 maxSize 는 서비스 운영 판단이라 엔진이 기본값을 고를 수 없다. 그래서 값은
 * 밖에서 오고, 구현(CacheEntry.isExpired · PageCacheStore.evictOne)만 여기 있다.
 *
 * 기본값은 둘 다 "끔" 이다. 이 구성에서 캐시는 축출도 만료도 하지 않고 rebuild 뒤의
 * clearCache() 로만 비운다 — 배치로만 바뀌는 데이터에는 TTL 보다 경계가 선명하다.
 * maxSize 가 꺼져 있으면 PageCacheStore 는 access-order 도 끈다. 순서를 쓸 곳이 없다.
 */
public class CachePolicy {

    public static final Duration TTL_DISABLE = Duration.ZERO; // TTL 비활성화
    public static final int UNLIMITED = -1;                    // 크기 무제한

    public static final CachePolicy DEFAULT = CachePolicy.builder().build();

    private final Duration ttl;
    private final int maxSize;

    private CachePolicy(Builder builder) {
        this.ttl = builder.ttl;
        this.maxSize = builder.maxSize;
    }

    public boolean isTtlEnabled() {
        return ttl != null && !ttl.isZero();
    }

    public Duration getTtl() {
        return ttl;
    }

    public boolean isMaxSizeEnabled() {
        return maxSize != UNLIMITED;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Duration ttl = TTL_DISABLE;
        private int maxSize = UNLIMITED;

        public Builder ttl(Duration ttl) {
            this.ttl = (ttl == null) ? TTL_DISABLE : ttl;
            return this;
        }

        public Builder maxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        public CachePolicy build() {
            return new CachePolicy(this);
        }
    }

    @Override
    public String toString() {
        return "CachePolicy{ttl=" + (isTtlEnabled() ? ttl : "DISABLE") +
                ", maxSize=" + (isMaxSizeEnabled() ? maxSize : "UNLIMITED") + '}';
    }
}