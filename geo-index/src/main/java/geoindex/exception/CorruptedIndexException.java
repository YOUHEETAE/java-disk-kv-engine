package geoindex.exception;

/**
 * 인덱스 파일의 내용이 불변식을 어겼다 — 손상이다.
 *
 * 부분 결과를 돌려주지 않고 던지는 이유: 원본 데이터는 MariaDB 에 온전히 있으므로
 * 열화된 답을 낼 필요가 없고, 조용히 줄어든 결과는 복구 계기를 남기지 않는다.
 * 잡아서 폴백할지 요청을 실패시킬지는 호출자가 정한다.
 *
 * pageId 는 손상된 체인의 primary 다. 호출자가 메시지를 파싱하지 않고도
 * 어느 칸인지 알 수 있도록 필드로 둔다. 체인이 가리키던 상대 pageId 는 메시지에만 있다.
 */
public class CorruptedIndexException extends RuntimeException {

    private final int pageId;

    public CorruptedIndexException(String message, int pageId) {
        super(message);
        this.pageId = pageId;
    }

    public int getPageId() {
        return pageId;
    }
}
