package minidb.storage;

import java.nio.ByteBuffer;

public class Page {
    private final ByteBuffer buffer;
    public static final int PAGE_SIZE = 4096;
    private final int pageId;
    private final byte[] data;
    private boolean dirty;

    public Page(int pageId) {
        this.pageId = pageId;
        this.data = new byte[PAGE_SIZE];
        this.dirty = false;
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
