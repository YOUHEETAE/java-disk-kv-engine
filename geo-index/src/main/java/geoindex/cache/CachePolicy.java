package geoindex.cache;

import java.time.Duration;

/**
 * 캐시 운영 정책 — 붙이는 쪽이 정하고 엔진은 실행만 한다.
 *
 * TTL · maxSize · warmupSize 는 서비스 운영 판단이라 엔진이 고를 수 없다. 그래서 값은
 * 밖에서 오고, 구현(CacheEntry.isExpired · PageCacheStore.evictOne · getWarmupTargets)만
 * 엔진에 있다.
 *
 * TTL 과 maxSize 의 기본값은 "끔" 이다. 이 구성에서 캐시는 축출도 만료도 하지 않고
 * rebuild 뒤의 clearCache() 로만 비운다 — 배치로만 바뀌는 데이터에는 TTL 보다 경계가
 * 선명하다. maxSize 가 꺼져 있으면 PageCacheStore 는 access-order 도 끈다.
 *
 * warmupSize 의 기본값은 3000 이다. 재시작 때 기록된 페이지 중 상위 몇 개를 미리
 * 채울지이고, WARMUP_ALL(-1) 이면 기록 전부다. 기본이 "전부" 가 아닌 이유는 기록이
 * 수만 개인 서버가 기동 때 그만큼 DB 를 치기 때문이다. maxSize 가 켜져 있으면 그
 * 이하로 한 번 더 잘린다 — 캐시에 못 들어갈 것을 가져올 이유가 없다.
 *
 * UNLIMITED 와 WARMUP_ALL 은 값이 같은 -1 이지만 뜻이 다르다 — 하나는 "상한 없음",
 * 하나는 "전부". 그래서 상수를 따로 둔다.
 *
 * warmupChunkSize 의 기본값은 1000 이다. 예열 대상 페이지들의 코드를 모은 목록을
 * loadByCodes 에 몇 개씩 넘길지 정한다. 정책에 둔 이유는 이건 DB 의 제약이지 엔진의
 * 성질이 아니기 때문이다 — IN 절에 넣을 수 있는 개수는 DB 와 매퍼마다 다르고,
 * 엔진은 어느 DB 인지 모른다. <= 0 은 허용하지 않는다.
 */
public class CachePolicy {

    public static final Duration TTL_DISABLE = Duration.ZERO; // TTL 비활성화
    public static final int UNLIMITED = -1;                    // 크기 무제한
    public static final int WARMUP_ALL = -1;

    public static final CachePolicy DEFAULT = CachePolicy.builder().build();

    private final Duration ttl;
    private final int maxSize;
    private final int warmupSize;
    private final int warmupChunkSize;

    private CachePolicy(Builder builder) {
        this.ttl = builder.ttl;
        this.maxSize = builder.maxSize;
        this.warmupSize = builder.warmupSize;
        this.warmupChunkSize = builder.warmupChunkSize;
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
        private int warmupSize = 3000;
        private int warmupChunkSize = 1000;

        public Builder ttl(Duration ttl) {
            this.ttl = (ttl == null) ? TTL_DISABLE : ttl;
            return this;
        }

        public Builder maxSize(int maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        public Builder warmupSize(int warmupSize) {
            this.warmupSize = warmupSize;
            return this;
        }

        public Builder warmupChunkSize(int warmupChunkSize) {
            if(warmupChunkSize <= 0) {
                throw new IllegalArgumentException("warmupChunkSize must be > 0");
            }
            this.warmupChunkSize = warmupChunkSize;
            return this;
        }

        public CachePolicy build() {
            return new CachePolicy(this);
        }
    }

    @Override
    public String toString() {
        return "CachePolicy{ttl=" + (isTtlEnabled() ? ttl : "DISABLE") +
                ", maxSize=" + (isMaxSizeEnabled() ? maxSize : "UNLIMITED") +
                ", warmupSize=" + (isWarmupAll() ? "ALL" : warmupSize) +
                ", warmupChunkSize=" + warmupChunkSize + '}';
    }

    public int getWarmupSize() {
        return warmupSize;
    }

    public boolean isWarmupAll(){
        return warmupSize == WARMUP_ALL;
    }

    public int getWarmupChunkSize() {
        return warmupChunkSize;
    }
}