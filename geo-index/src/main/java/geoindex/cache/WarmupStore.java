package geoindex.cache;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * pageId 별 접근 횟수 — 재시작 뒤 어떤 페이지를 먼저 예열할지 고르는 근거.
 *
 *   정확성에 관여하지 않는다. 그래서 읽기·쓰기 실패를 모두 삼키고, 어떤 이유로 파일이
 *   깨져 있어도 엔진 기동을 막지 않는다. — 없으면 예열이 안된다.
 *
 * 파일 형식은 한 줄에 "pageId 공백 count" 다. 종료 시 saveHitCounts 가 쓰고, 생성자가
 * loadHitCounts 로 읽는다. 감쇠는 없다 — 카운트는 늘기만 하므로 오래 뜬 서버일수록
 * 과거의 인기가 Top N 을 점유한다.
 *
 * recordAccess 는 PageCacheStore 모니터 안에서 오지만, getTopPageIds 와 saveHitCounts 는
 * 예열·종료 스레드에서 온다. 그래서 ConcurrentHashMap + AtomicLong 이다.
 */
public class WarmupStore {
    private final Path storePath;
    private final ConcurrentHashMap<Integer, AtomicLong> hitCounts;
    private static final Logger log = Logger.getLogger(WarmupStore.class.getName());


    public WarmupStore(Path storePath) {
        this.storePath = storePath;
        this.hitCounts = new ConcurrentHashMap<>();
        loadHitCounts();
    }

    /**
     * 히트가 아니라 수요를 센다. 호출자(PageCacheStore.getOrMiss)가 HIT/MISS 판정 전에
     * 부르므로 미스도 포함된다 — 히트만 세면 예열이 필요한 페이지가 후보에서 빠진다.
     */
    public void recordAccess(int pageId){
        hitCounts.computeIfAbsent(pageId, k -> new AtomicLong()).incrementAndGet();
    }

    /**
     * 접근 횟수 내림차순 Top N. 순서가 계약이다 — 예열이 maxSize 에 걸리면 뒤쪽부터
     * 밀려나야 하므로, 받는 쪽은 이 순서를 유지하는 컬렉션에 담아야 한다.
     */
    public List<Integer> getTopPageIds(int n) {
        return hitCounts.entrySet().stream()
                 .sorted(Comparator.comparingLong((Map.Entry<Integer, AtomicLong> e) -> e.getValue().get()).reversed())
                 .limit(n)
                 .map(Map.Entry::getKey)
                 .collect(Collectors.toList());

    }

    public void saveHitCounts() {
        try (BufferedWriter writer = Files.newBufferedWriter(storePath)) {
            for(Map.Entry<Integer, AtomicLong> entry : hitCounts.entrySet()){
                writer.write(entry.getKey() + " " + entry.getValue().get());
                writer.newLine();
            }
        } catch (IOException e) {
            log.warning("[WarmupStore] save 실패: " + e.getMessage());
        }
    }

    /**
     * catch 가 RuntimeException 까지 넓은 이유:
     *   파일은 saveHitCounts 가 쓰므로 정상 경로에서는 늘 숫자다. 깨지는 경우는 에디터가
     *   붙인 BOM, 손편집, 엉뚱한 경로, 찢어진 쓰기, int 를 넘는 pageId 인데, 그 어느 것도
     *   NumberFormatException 으로 build() 를 죽일 이유가 못 된다. 힌트니까.
     *
     * 깨진 줄 앞까지 읽은 것은 버리지 않는다. 그 줄들은 saveHitCounts 가 정상적으로 쓴
     * 유효한 쌍이고, 예열은 힌트가 하나라도 더 있는 편이 낫다.
     */
    private void loadHitCounts(){
        if(!Files.exists(storePath)) return;
        try(BufferedReader reader = Files.newBufferedReader(storePath)){
            String line;
            while((line = reader.readLine()) != null){
                String[] split = line.trim().split(" ");
                if(split.length == 2){
                    int pageId = Integer.parseInt(split[0]);
                    long hitCount = Long.parseLong(split[1]);
                    hitCounts.put(pageId, new AtomicLong(hitCount));
                }
            }
        } catch(IOException | RuntimeException e) {
            log.warning("[WarmupStore] load 중단, 읽은 줄까지만 사용: " + e.getMessage());
        }
    }

    public long getHitCount(int pageId){
        AtomicLong hitCount = hitCounts.get(pageId);
        return hitCount != null ? hitCount.get() : 0;
    }

}
