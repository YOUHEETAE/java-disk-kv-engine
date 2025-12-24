package minidb.storage;

public class Page {
    public static final int PAGE_SIZE = 4096;
    private final int pageId;
    private final byte[] data;
    private boolean dirty;

    public Page(int pageId){
        this.pageId = pageId;
        this.data = new byte[PAGE_SIZE];
        this.dirty = false;
    };

    public int getPageId(){
        return pageId;
    }

    public byte[] getData(){
        return data;
    }

    public boolean isDirty(){
        return dirty;
    }
    public void markDirty(){
        this.dirty =  true;
    }
}
