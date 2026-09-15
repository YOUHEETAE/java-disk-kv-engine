package geoindex.exception;

public class WarmupFailedException extends RuntimeException {
    public WarmupFailedException(Throwable cause) {
        super("rebuild succeeded but warmup failed; the cache is cold", cause);
    }
}
