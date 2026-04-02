package geoindex.cache;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class WarmupStore {
    private final Path storePath;
    private final ConcurrentHashMap<Integer, AtomicLong> hitCounts;
    private static final Logger log = Logger.getLogger(WarmupStore.class.getName());


    public WarmupStore(Path storePath) {
        this.storePath = storePath;
        this.hitCounts = new ConcurrentHashMap<>();
        load();
    }

    public void recordAccess(int pageId){
        hitCounts.computeIfAbsent(pageId, k -> new AtomicLong()).incrementAndGet();
    }

    public List<Integer> getTopPageIds(int n) {
        return hitCounts.entrySet().stream()
                 .sorted(Comparator.comparingLong((Map.Entry<Integer, AtomicLong> e) -> e.getValue().get()).reversed())
                 .limit(n)
                 .map(Map.Entry::getKey)
                 .collect(Collectors.toList());

    }

    public void persist() {
        try (BufferedWriter writer = Files.newBufferedWriter(storePath)) {
            for(Map.Entry<Integer, AtomicLong> entry : hitCounts.entrySet()){
                writer.write(entry.getKey() + " " + entry.getValue().get());
                writer.newLine();
            }
        } catch (IOException e) {
            log.warning("[WarmupStore] persist 실패: " + e.getMessage());
        }
    }

    private void load(){
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
        } catch(IOException e) {
            log.warning("[WarmupStore] load 실패, fresh start로 동작: " + e.getMessage());
        }
    }

    public long getHitCount(int pageId){
        AtomicLong hitCount = hitCounts.get(pageId);
        return hitCount != null ? hitCount.get() : 0;
    }

}
