package geoindex.api;

import geoindex.exception.WarmupFailedException;
import geoindex.metric.MetricsSnapshot;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 스프링 서비스가 상속하는 템플릿. 구현할 것은 loadByCodes 하나 — 코드 목록을 받아 DB 에서
 * 객체를 가져오는 부분이다. search · warmup · rebuild · shutdown 은 여기서 제공한다.
 *
 * 엔진은 DB 를 모른다. 이 클래스가 loadByCodes 를 batchLoader 로 감싸 SpatialCacheEngine 에
 * 넘기므로, 저장소가 JPA 든 MyBatis 든 HTTP 든 엔진 코드는 바뀌지 않는다.
 *
 * rebuild 는 재구축 · 비우기 · 예열 세 사건이다. 앞의 둘은 SpatialCacheEngine 이, 예열은
 * 여기서 한다. 예열이 실패해도 앞의 둘은 이미 끝났으므로 WarmupFailedException 으로 구분해
 * 던진다 — 캐시가 빈 채 서비스해도 되는지는 서비스가 정한다.
 *
 * 스프링 연결: @Bean(destroyMethod = "close") 는 엔진의 flush 와 파일 닫기이고, 접근 횟수
 * 저장은 별도로 @PreDestroy 에서 shutdown() 을 불러야 한다. 둘은 다른 일이다.
 */
public abstract class AbstractSpatialCacheEngine<T> {

    protected final SpatialCacheEngine<T> spatialCacheEngine;

    protected AbstractSpatialCacheEngine(SpatialCacheEngine<T> spatialCacheEngine) {
        this.spatialCacheEngine = spatialCacheEngine;
    }

    /**
     * 코드 목록 → 코드별 객체. 서비스가 구현하는 유일한 것이다. 돌려주지 않은 코드는 결과에서
     * 조용히 빠진다 — 인덱스에는 있는데 DB 에 없다는 뜻이고, 다음 rebuild 가 맞춘다.
     */
    protected abstract Map<String, T> loadByCodes(List<String> codes);

    /**
     * 반경을 커버하는 pageId 범위의 후보 결과를 반환한다.
     * 경계 셀의 정확한 필터링(MBR / 원형)은 구현체가 담당한다.
     */
    public List<T> search(double lat, double lng, double radiusKm) {
        return spatialCacheEngine.search(lat, lng, radiusKm, codes -> loadByCodes(codes));
    }

    /**
     * 인덱스를 다시 만들고, 캐시를 비우고, 예열한다. IndexLoader 는 서비스가 byte[] 를 모르게
     * 하는 어댑터다 — 코드 문자열을 여기서 UTF-8 로 바꾼다. 파일의 인코딩이 결정되는 자리다.
     */
    public void rebuild(Consumer<IndexLoader> supplier) {
        spatialCacheEngine.rebuild(srm ->
            supplier.accept((lat, lng, code) ->
                    srm.put(lat, lng, code.getBytes(StandardCharsets.UTF_8)))
        );
        try {
            warmup();
        } catch(RuntimeException e) {
            // 재구축은 이미 끝났고 인덱스는 새것이다. 원래 예외를 그대로 올리면 호출자가 rebuild 실패로
            // 오해해 멀쩡한 인덱스를 다시 만들 수 있다. 예열 실패임을 타입으로 구분해 준다.
            // 무시할지는 호출자가 정한다 — 캐시가 빈 채 서비스해도 되는지는 엔진이 모른다.
            spatialCacheEngine.recordWarmupFailure();
            throw new WarmupFailedException(e);
        }
    }

    /**
     * 재시작 뒤 캐시를 미리 채운다. 대상은 접근 횟수 상위 페이지(정책의 warmupSize · maxSize 가
     * 개수를 정한다). 코드를 모아 청크로 DB 를 부르고, 페이지별로 나눠 캐시에 넣는다.
     *
     * 넣는 순서가 인기 오름차순인 이유는 아래 주석에. 예열 직후 LRU 순서 = 인기 순서가 되어야
     * 첫 축출에서 가장 인기 없는 것이 나간다.
     */
    public void warmup() {
        Map<Integer, List<String>> targets = spatialCacheEngine.getWarmupTargets();
        List<String> allCodes = targets.values().stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        int chunkSize = spatialCacheEngine.getWarmupChunkSize(); // IN 절 길이 제한 회피. 쿼리는 나누고 결과는 합친다
        Map<String, T> byCode = new HashMap<>();
        for (int i = 0; i < allCodes.size(); i += chunkSize) {
            List<String> chunk = allCodes.subList(i, Math.min(i + chunkSize, allCodes.size()));
            byCode.putAll(loadByCodes(chunk));
        }

        List<Map.Entry<Integer, List<T>>> prepared = new ArrayList<>();
        targets.forEach((pageId, codes) -> {
            List<T> data = codes.stream()
                    .map(byCode::get)
                    .filter(Objects::nonNull)
                    .toList();
            prepared.add(Map.entry(pageId, data));
        });
        // 인기 오름차순 — 가장 인기 있는 것이 마지막에 들어가
        // LRU 가 처음 축출할 때 가장 인기 없는 것부터 나간다
        Collections.reverse(prepared);
        prepared.forEach(e -> spatialCacheEngine.putCache(e.getKey(), e.getValue()));
    }

    /** 접근 횟수를 파일로 저장한다. @PreDestroy 에서 부른다. close() 와는 다른 일이다. */
    public void shutdown() {
        spatialCacheEngine.saveWarmup();
    }

    public MetricsSnapshot getMetrics() {
        return spatialCacheEngine.getMetrics();
    }

}
