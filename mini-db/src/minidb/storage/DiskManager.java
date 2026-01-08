package minidb.storage;


import java.io.IOException;
import java.io.RandomAccessFile;

public class DiskManager {

    private final RandomAccessFile dbFile;

    public DiskManager(String filePath) {
        try {
            this.dbFile = new RandomAccessFile(filePath, "rw");
        } catch (IOException e) {
            throw new RuntimeException("pageSize",e);
        }
    }

    public Page readPage(int pageId) {
        try {
            long offset = (long) pageId * Page.PAGE_SIZE;

            if (offset + Page.PAGE_SIZE > dbFile.length()) {
                return null;
            }

            dbFile.seek(offset);
            Page page = new Page(pageId);
            dbFile.readFully(page.getData());
            return page;

        } catch (IOException e) {
            throw new RuntimeException("readPage failed: pageId=" + pageId, e);
        }
    }

    public void writePage(Page page){
        try {
            long offset = (long)page.getPageId() * Page.PAGE_SIZE;
            dbFile.seek(offset);
            dbFile.write(page.getData(), 0, Page.PAGE_SIZE);
        }catch (IOException e){
            throw new RuntimeException("writePage failed"+page.getPageId(),e);
        }
    }

    public void close() {
        try {
            dbFile.close();
        } catch (IOException e) {
            throw new RuntimeException("close failed", e);
        }
    }

}
