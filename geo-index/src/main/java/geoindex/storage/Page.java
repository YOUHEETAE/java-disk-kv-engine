package geoindex.storage;

import java.nio.ByteBuffer;

/**
 * 메모리에 올라온 4KB 페이지 한 장.
 *
 * data 와 buffer 는 같은 배열을 가리킨다. 계층마다 필요한 접근 방식이 달라 둘 다 둔다.
 *   data   — DiskManager 가 파일에 통째로 읽고 쓸 때
 *   buffer — PageLayout 이 int 단위로 읽고 쓸 때
 *
 * 페이지 안쪽의 해석(헤더·슬롯·레코드)은 PageLayout 이 담당한다.
 * 이 클래스는 바이트 덩어리와 dirty 플래그만 들고 있다.
 */
public class Page {
    private final ByteBuffer buffer;
    /**
     * 엔진의 고정 페이지 크기(4KB)
     * OS 페이지·파일시스템 블록 크기와 맞춰 경계에 걸친 읽기/쓰기를 피한다.
     * 너무 작으면 페이지 관리 및 오버플로우가 증가하고
     * 너무 크면 불필요한 i/o비용이 증가한다. (writePage 는 항상 페이지 전체를 쓴다)
     */
    public static final int PAGE_SIZE = 4096;
    private final int pageId;
    private final byte[] data;
    /**
     * Write-Back 에서 "디스크에 반영해야 함"을 나타내는 유일한 신호.
     *
     * volatile 인 이유: CacheManager.getDirtyPageCount() 가 락을 잡지 않고 읽는다.
     * 그 메트릭 경로가 사라지지 않는 한 이 키워드를 빼면 데이터 레이스가 된다.
     */
    private volatile boolean dirty;

    public Page(int pageId) {
        this.pageId = pageId;
        this.data = new byte[PAGE_SIZE];
        this.dirty = false;
        // 기존 페이지 데이터 배열을 직접 감싸 추가 할당과 복사를 피한다.
        this.buffer = ByteBuffer.wrap(this.data);
    };

    public int getPageId(){
        return pageId;
    }

    public byte[] getData(){
        return data;
    }

    public ByteBuffer buffer(){
        return buffer;
    }

    public boolean isDirty(){
        return dirty;
    }
    public void markDirty(){
        this.dirty =  true;
    }

    public void clearDirty(){
        this.dirty = false;
    }
}
