package geoindex.storage;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 4KB 페이지 안쪽의 주소 체계 — Slotted Page 구조.
 *
 * 페이지 배치
 *   [0 ~ 15]    헤더 — recordCount(4) · freeSpaceStart(4) · magic(4) · overflowPageId(4)
 *   [16 ~ ]     슬롯 디렉토리 — 앞에서 뒤로 자란다. 슬롯 하나가 offset(4) + length(4)
 *   [ ~ 4095]   레코드 — 뒤에서 앞으로 자란다. 레코드 하나가 valueLength(4) + value
 *
 * 슬롯과 레코드가 반대 방향으로 자라는 이유:
 *   레코드 길이가 가변이라 양끝에서 자라게 하면 빈 공간이 항상 가운데 한 덩어리로 모인다.
 *   덕분에 남은 공간 판정이 뺄셈 한 번(newOffset < slotDirEnd)으로 끝난다.
 *
 * 레코드에 key 는 저장하지 않는다.
 *   공간 인덱스에서는 pageId 자체가 "어느 격자냐"라는 키이므로,
 *   페이지 안에서 키를 다시 비교할 이유가 없다. 읽기는 슬롯 순회로 전량 반환한다.
 *
 * 모든 접근은 절대 위치 메서드만 쓴다. 이유는 readRecord 참고.
 */
public class PageLayout {

    // 헤더 16바이트 안에서 각 필드의 위치
    public static final int OFFSET_RECORD_COUNT = 0;    // 이 페이지에 든 레코드 수
    public static final int OFFSET_FREE_SPACE   = 4;    // 레코드가 뒤에서 자라며 내려온 경계
    public static final int OFFSET_MAGIC        = 8;    // 0xCAFEBABE — 초기화 여부 판별
    public static final int OFFSET_OVERFLOW     = 12;   // 다음 overflow 페이지 번호
    public static final int HEADER_SIZE         = 16;
    public static final int SLOT_SIZE           = 8;    // offset(4) + length(4)

    /** overflowPageId 가 이 값이면 체인의 끝이다. */
    public static final int NO_OVERFLOW         = -1;

    private PageLayout() {}

    /**
     * 이 페이지가 한 번이라도 초기화됐는지.
     *
     * 파일에 있는 페이지는 flush 를 거쳤으므로 정상 경로에서는 항상 true 다.
     * false 가 나오는 경로는 둘뿐이다.
     *   - writePage 가 매핑 엔트리만 쓰고 데이터를 쓰기 전에 중단됐다 (그 영역이 전부 0)
     *   - DiskManager 를 직접 써서 초기화하지 않은 페이지를 저장했다 (테스트)
     *
     * 즉 일상적인 판별자가 아니라 손상 감지용 불변식 확인이다. 안 걸린다고 지우면
     * 미완성 쓰기를 "레코드 0개인 정상 페이지"로 읽어, 그 칸의 데이터가 조용히 사라진다.
     */
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

    /**
     * 레코드 하나를 기록하고 슬롯 번호를 반환한다.
     *
     * 반환값 -1 은 "이 페이지가 꽉 찼다"는 뜻이고, 상위 계층(SpatialRecordManager)이
     * overflow 페이지를 새로 붙이는 신호다. 두 계층 사이의 유일한 계약이라
     * 의미를 바꾸면 체인 생성이 깨진다.
     *
     * 담을 수 있는 최대 크기는 4,068바이트다. (4096 - 헤더 16 - 슬롯 8 - 길이 4)
     * 그보다 큰 value 는 빈 페이지에서도 -1 이 나와, 상위 계층이 overflow 를
     * 무한히 할당하다 풀을 소진한다. 진입점에서 크기를 막는 편이 안전하다.
     */
    public static int writeRecord(Page page, byte[] value) {
        int recordCount    = getRecordCount(page);
        int freeSpaceStart = getFreeSpaceStart(page);
        int recordSize     = 4 + value.length;
        int newOffset      = freeSpaceStart - recordSize;
        int slotDirEnd     = HEADER_SIZE + (recordCount + 1) * SLOT_SIZE;

        if (newOffset < slotDirEnd) return -1;

        ByteBuffer buffer = page.buffer();
        buffer.putInt(newOffset, value.length);
        System.arraycopy(value, 0, buffer.array(), newOffset + 4, value.length);

        setSlot(page, recordCount, newOffset, recordSize);
        setRecordCount(page, recordCount + 1);
        setFreeSpaceStart(page, newOffset);
        page.markDirty();

        return recordCount;
    }

    /**
     * 슬롯 번호로 레코드 하나를 읽는다.
     *
     * buffer.position() 을 쓰지 않는 이유:
     *   position 은 ByteBuffer 가 들고 있는 공유 상태다. 같은 Page 를 여러 스레드가
     *   동시에 읽으면 서로의 position 을 덮어써 엉뚱한 위치를 읽는다.
     *   getInt(index) 같은 절대 위치 메서드는 내부 상태를 건드리지 않아 락 없이도 안전하다.
     *
     *   즉 이 안전성은 락이 아니라 "공유 상태를 쓰지 않는다"에서 온다.
     *   보기 편하다고 position 방식으로 되돌리면 조용히 깨진다.
     */
    public static byte[] readRecord(Page page, int slotId) {
        if (!isInitialized(page)) return null;
        if (slotId < 0 || slotId >= getRecordCount(page)) return null;

        int offset = getSlotOffset(page, slotId);
        ByteBuffer buffer = page.buffer();
        int valueLength = buffer.getInt(offset);
        byte[] value = new byte[valueLength];
        System.arraycopy(buffer.array(), offset + 4, value, 0, valueLength);
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

    /**
     * 여기서만 markDirty 를 부르는 이유: 나머지 세터는 private 이라 writeRecord ·
     * initializePage 안에서만 불리고 그 끝에서 함께 표시된다. 이 세터만 public 이라
     * SpatialRecordManager 가 단독으로 부르므로, 스스로 표시하지 않으면
     * 이 호출이 유일한 변경인 페이지(꽉 차서 writeRecord 가 -1 을 낸 경우)가
     * flush 를 건너뛴다.
     */
    public static void setOverflowPageId(Page page, int pageId) {
        page.buffer().putInt(OFFSET_OVERFLOW, pageId);
        page.markDirty();
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