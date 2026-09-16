package geoindex.exception;

/**
 * rebuild 는 끝났는데 뒤이은 예열이 실패했다. 인덱스는 새것이고 캐시는 빈 채다.
 *
 * 원래 예외를 그대로 올리면 호출자가 rebuild 실패로 오해해 멀쩡한 인덱스를 다시 만들 수
 * 있다. 그렇다고 삼키지도 않는다 — 캐시가 빈 채 서비스해도 되는지는 엔진이 정할 정책이
 * 아니다. 타입으로 구분해 던지고, 무시할지는 호출자가 정한다. 원인은 getCause() 에 있다.
 *
 * 던지기 전에 EngineMetrics 의 warmupFailureCount 를 올리므로, 호출자가 잡아서 무시하더라도
 * 엔진 쪽에는 남는다.
 */
public class WarmupFailedException extends RuntimeException {
    public WarmupFailedException(Throwable cause) {
        super("rebuild succeeded but warmup failed; the cache is cold", cause);
    }
}
