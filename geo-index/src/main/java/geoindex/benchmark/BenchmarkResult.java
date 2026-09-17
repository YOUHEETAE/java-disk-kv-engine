package geoindex.benchmark;

public record BenchmarkResult(long medianNs, int candidates, int matched) {
}
