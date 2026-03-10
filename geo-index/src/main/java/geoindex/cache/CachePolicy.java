package geoindex.cache;

import java.time.Duration;

/**
 * 캐시 운영 정책 — Spring Config에서 생성해서 엔진에 주입
 *
 * Why:
 *   "얼마나 유지할지(TTL)", "얼마나 저장할지(maxSize)"는
 *   서비스 운영 정책이므로 Spring이 결정한다.
 *   엔진은 정책을 받아서 실행만 한다.
 *
 *   TTL  구현 → 엔진 책임 (CacheEntry.isExpired())
 *   TTL  설정 → Spring 책임 (@Value 주입 → CachePolicy 생성)
 *
 * Spring Config 사용 예:
 *   @Value("${cache.ttl.days:0}")         // 0 = DISABLE
 *   private long ttlDays;
 *
 *   @Value("${cache.max-size:-1}")         // -1 = UNLIMITED
 *   private int maxSize;
 *
 *   CachePolicy.builder()
 *       .ttl(ttlDays == 0 ? CachePolicy.TTL_DISABLE : Duration.ofDays(ttlDays))
 *       .maxSize(maxSize)
 *       .build();
 *
 * 현재 서비스 기본값:
 *   TTL     → DISABLE (배치 완료 시 Spring @Scheduled → clearCache())
 *   maxSize → UNLIMITED (전체 캐시 23MB, 메모리 부담 없음)
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