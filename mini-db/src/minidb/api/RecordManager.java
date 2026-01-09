package minidb.api;

import minidb.buffer.CacheManager;
import minidb.storage.Page;

import java.nio.ByteBuffer;

public class RecordManager {
    private final CacheManager cacheManager;
    private static final int MAX_PAGES = 1000;

    public RecordManager(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    private int getPageId(String key) {
        int pageId = Math.abs(key.hashCode() % MAX_PAGES);
        return pageId;
    }

    public void put(String key, byte[] value) {
        int pageId = getPageId(key);
        Page page = cacheManager.getPage(pageId);
        writeRecord(page, key, value);
        cacheManager.putPage(page);
    }

    private void writeRecord(Page page, String key, byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(page.getData());
        byte[] keyBytes = key.getBytes();
        buffer.putInt(keyBytes.length);
        buffer.put(keyBytes);
        buffer.putInt(value.length);
        buffer.put(value);
    }

    public byte[] get(String key) {
        int pageId = getPageId(key);
        Page page = cacheManager.getPage(pageId);
        return readRecord(page, key);
    }
    private byte[] readRecord(Page page, String key) {
        ByteBuffer buffer = ByteBuffer.wrap(page.getData());
        int keyLength = buffer.getInt();
        byte[] keyBytes = new byte[keyLength];
        buffer.get(keyBytes);
        String storedKey = new String(keyBytes);

        if (!storedKey.equals(key)) {
            return null;
        }

        int valueLength = buffer.getInt();
        byte[] valueBytes = new byte[valueLength];
        buffer.get(valueBytes);
        return valueBytes;
    }

}
