package minidb.api;

public class RecordId {
    private final int pageId;
    private final int slotId;

    public RecordId(int pageId, int slotId) {
        this.pageId = pageId;
        this.slotId = slotId;
    }
    public int getPageId() {
        return pageId;
    }
    public int getSlotId() {
        return slotId;
    }

    @Override
    public String toString() {
        return "RecordId{PageId=" + pageId + ", SlotId=" + slotId + "}";
    }

    @Override
    public boolean equals(Object o){
        if(this == o) return true;
        if(!(o instanceof RecordId recordId)) return false;
        return pageId == recordId.pageId && slotId == recordId.slotId;
    }

    @Override
    public int hashCode() {
        return 31 * pageId + slotId;
    }
}
