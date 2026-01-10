package minidb.api;

import minidb.buffer.CacheManager;
import minidb.storage.Page;

import java.nio.ByteBuffer;

public class RecordManager {
    private static final int OFFSET_RECORD_COUNT = 0;
    private static final int OFFSET_FREE_SPACE   = 4;
    private static final int OFFSET_MAGIC        = 8;
    // future use (LSN, checksum, flags)
    private static final int HEADER_SIZE         = 16;
    private static final int OFFSET_OVERFLOW     = 12;
    private static final int NO_OVERFLOW = -1;
    private static final int SLOT_SIZE           = 8;

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

        if (!isInitialized(page)) {
            initializePage(page);
        }

        writeRecord(page, key, value);
        cacheManager.putPage(page);
    }

    private void writeRecord(Page page, String key, byte[] value) {
        int recordCount = getRecordCount(page);
        int freeSpaceStart = getFreeSpaceStart(page);

        byte[] keyBytes = key.getBytes();
        int recordSize  = 4 + keyBytes.length + 4 + value.length;
        int newOffset = freeSpaceStart - recordSize;
        int slotDirEnd = HEADER_SIZE + (recordCount + 1) * SLOT_SIZE;

        if (newOffset < slotDirEnd) {
           int overflowPageId = getOverflowPageId(page);
           if(overflowPageId == NO_OVERFLOW) {
               overflowPageId = allocateNewPage();
               setOverflowPageId(page, overflowPageId);
           }
           Page overflowPage = cacheManager.getPage(overflowPageId);
           if(!isInitialized(overflowPage)) {
               initializePage(overflowPage);
           }
           writeRecord(overflowPage, key, value);
           return;
        }

        ByteBuffer buffer = page.buffer();
        buffer.position(newOffset);
        buffer.putInt(keyBytes.length);
        buffer.put(keyBytes);
        buffer.putInt(value.length);
        buffer.put(value);

        setSlot(page, recordCount, newOffset, recordSize);

        setRecordCount(page, recordCount + 1);
        setFreeSpaceStart(page, newOffset);

        page.markDirty();
    }

    public byte[] get(String key) {
        int pageId = getPageId(key);
        Page page = cacheManager.getPage(pageId);
        return readRecord(page, key);
    }
    private byte[] readRecord(Page page, String key) {
        if (!isInitialized(page)) {
            return null;
        }
        int recordCount = getRecordCount(page);
        ByteBuffer buffer = page.buffer();

        for (int i = recordCount - 1; i >= 0; i--) {
            int offset = getSlotOffset(page, i);

            buffer.position(offset);
            int keyLength = buffer.getInt();
            byte[] keyBytes = new byte[keyLength];
            buffer.get(keyBytes);
            String storedKey = new String(keyBytes);

            if (storedKey.equals(key)) {
                int valueLength = buffer.getInt();
                byte[] valueBytes = new byte[valueLength];
                buffer.get(valueBytes);
                return valueBytes;
            }
        }
        int overflowPageId = getOverflowPageId(page);

        if (overflowPageId != NO_OVERFLOW && overflowPageId != page.getPageId()) {
            Page overflowPage = cacheManager.getPage(overflowPageId);
            return readRecord(overflowPage, key);
        }
        return null;
    }

    private int getRecordCount(Page page) {
        ByteBuffer buffer = page.buffer();
        buffer.position(OFFSET_RECORD_COUNT);
        int value = buffer.getInt();
        return value;
    }

    private int getFreeSpaceStart(Page page){
        ByteBuffer buffer = page.buffer();
        buffer.position(OFFSET_FREE_SPACE);
        int value = buffer.getInt();
        return value;
    }

    private void setRecordCount(Page page, int count) {
        ByteBuffer buffer = page.buffer();
        buffer.position(OFFSET_RECORD_COUNT);
        buffer.putInt(count);
    }

    private void setFreeSpaceStart(Page page, int offset) {
        ByteBuffer buffer = page.buffer();
        buffer.position(OFFSET_FREE_SPACE);
        buffer.putInt(offset);
    }

    private int getSlotOffset(Page page, int slotIndex) {
        ByteBuffer buffer = page.buffer();
        int slotPosition = HEADER_SIZE + (slotIndex * SLOT_SIZE);
        buffer.position(slotPosition);
        int value = buffer.getInt();
        return value;
    }

    private int getSlotLength(Page page, int slotIndex) {
        ByteBuffer buffer = page.buffer();
        int slotPosition = HEADER_SIZE + (slotIndex * SLOT_SIZE);
        buffer.position(slotPosition + 4);
        int value = buffer.getInt();
        return value;
    }
    private void setSlot(Page page, int slotIndex, int offset, int length) {
        ByteBuffer buffer = page.buffer();
        int  slotPosition = HEADER_SIZE + (slotIndex * SLOT_SIZE);
        buffer.position(slotPosition);
        buffer.putInt(offset);
        buffer.putInt(length);
    }

    private int getOverflowPageId(Page page) {
        ByteBuffer buffer = page.buffer();
        buffer.position(OFFSET_OVERFLOW);
        return buffer.getInt();
    }

    private void  setOverflowPageId(Page page, int pageId) {
        ByteBuffer buffer = page.buffer();
        buffer.position(OFFSET_OVERFLOW);
        buffer.putInt(pageId);
    }

    private int allocateNewPage(){
        for(int pageId = 0; pageId < MAX_PAGES; pageId++){
            Page page = cacheManager.getPage(pageId);
            if(!isInitialized(page)){
                return pageId;
            }
        }
        throw new IllegalStateException("no available pages");
    }

    private boolean isInitialized(Page page) {
        ByteBuffer buffer = page.buffer();
        return buffer.getInt(OFFSET_MAGIC) == 0xCAFEBABE;
    }

    private void initializePage(Page page) {
        setRecordCount(page, 0);
        setFreeSpaceStart(page, Page.PAGE_SIZE);
        setOverflowPageId(page, NO_OVERFLOW);

        ByteBuffer buffer = page.buffer();
        buffer.putInt(OFFSET_MAGIC, 0xCAFEBABE);

        page.markDirty();
    }
}
