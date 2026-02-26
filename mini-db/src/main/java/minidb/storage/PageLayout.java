package minidb.storage;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class PageLayout {

    public static final int OFFSET_RECORD_COUNT = 0;
    public static final int OFFSET_FREE_SPACE   = 4;
    public static final int OFFSET_MAGIC        = 8;
    public static final int OFFSET_OVERFLOW     = 12;
    public static final int HEADER_SIZE         = 16;
    public static final int SLOT_SIZE           = 8;
    public static final int NO_OVERFLOW         = -1;

    private PageLayout(){}

    public static boolean isInitialized(Page page) {
        return page.buffer().getInt(OFFSET_MAGIC) == 0xCAFEBABE;
    }

    public static void initializePage(Page page) {
        setRecordCount(page, 0);
        setFreeSpaceStart(page, Page.PAGE_SIZE);
        setOverflowPageId(page, NO_OVERFLOW);
        page.buffer().putInt(OFFSET_MAGIC, 0xCAFEBABE);
        page.markDirty();
    }

    public static int writeRecord(Page page, byte[] value) {
        int recordCount    = getRecordCount(page);
        int freeSpaceStart = getFreeSpaceStart(page);
        int recordSize     = 4 + value.length;
        int newOffset      = freeSpaceStart - recordSize;
        int slotDirEnd     = HEADER_SIZE + (recordCount + 1) * SLOT_SIZE;

        if (newOffset < slotDirEnd) return -1; // 공간 부족

        ByteBuffer buffer = page.buffer();
        buffer.position(newOffset);
        buffer.putInt(value.length);
        buffer.put(value);

        setSlot(page, recordCount, newOffset, recordSize);
        setRecordCount(page, recordCount + 1);
        setFreeSpaceStart(page, newOffset);
        page.markDirty();

        return recordCount; // slotId 반환
    }

    public static byte[] readRecord(Page page, int slotId) {
        if (!isInitialized(page)) return null;
        if (slotId < 0 || slotId >= getRecordCount(page)) return null;

        int offset = getSlotOffset(page, slotId);
        ByteBuffer buffer = page.buffer();
        buffer.position(offset);

        int valueLength = buffer.getInt();
        byte[] value = new byte[valueLength];
        buffer.get(value);
        return value;
    }

    public static List<byte[]> readAllRecords(Page page) {
        List<byte[]> records = new ArrayList<>();
        if (!isInitialized(page)) return records;

        int recordCount = getRecordCount(page);
        for (int i = 0; i < recordCount; i++) {
            byte[] value = readRecord(page, i);
            if (value != null) records.add(value);
        }
        return records;
    }

    public static int getOverflowPageId(Page page) {
        return page.buffer().getInt(OFFSET_OVERFLOW);
    }

    public static void setOverflowPageId(Page page, int pageId) {
        page.buffer().putInt(OFFSET_OVERFLOW, pageId);
    }

    public static int getRecordCount(Page page) {
        return page.buffer().getInt(OFFSET_RECORD_COUNT);
    }

    private static void setRecordCount(Page page, int count) {
        page.buffer().putInt(OFFSET_RECORD_COUNT, count);
    }

    private static int getFreeSpaceStart(Page page) {
        return page.buffer().getInt(OFFSET_FREE_SPACE);
    }

    private static void setFreeSpaceStart(Page page, int offset) {
        page.buffer().putInt(OFFSET_FREE_SPACE, offset);
    }

    private static int getSlotOffset(Page page, int slotIndex) {
        return page.buffer().getInt(HEADER_SIZE + slotIndex * SLOT_SIZE);
    }

    private static void setSlot(Page page, int slotIndex, int offset, int length) {
        ByteBuffer buffer = page.buffer();
        buffer.putInt(HEADER_SIZE + slotIndex * SLOT_SIZE, offset);
        buffer.putInt(HEADER_SIZE + slotIndex * SLOT_SIZE + 4, length);
    }
}