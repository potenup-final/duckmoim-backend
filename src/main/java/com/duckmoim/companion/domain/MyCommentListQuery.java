package com.duckmoim.companion.domain;

/**
 * 내 댓글 내역의 조회 조건 (CM-16).
 *
 * <p>거르는 조건이 둘이다 — <b>작성자와 상태.</b> {@link CommentListQuery} 가 상태로 거르지 않는 것과 갈리는 지점이다.
 *
 * <p><b>살아 있는 댓글만 읽는다.</b> 도메인-모델링.md 「7.1 가시성과 권한」이 삭제·블라인드 본문을 <i>작성자 본인에게도</i> 막으므로, 남겨도 본문 없는
 * 껍데기만 뜬다. 모집글 댓글 목록에서 자리표시자를 남기는 이유는 <b>매달린 대댓글이 고아가 되지 않게</b> 하려는 것인데 (CM-11), 내 내역은 대댓글을 부모와 함께
 * 내리지 않아 그 이유가 성립하지 않는다. 상태 조건이 SQL 로 표현되므로 여기 담는다 — 목록 쪽은 「하위 대댓글이 있는지」로 갈려 service 가 걸렀다.
 *
 * @param authorId 요청자 자신. <b>요청 본문이나 파라미터로 받지 않는다</b> — 인증 주체에서 온다. 남의 내역을 보는 경로가 생기면 안 된다
 * @param cursor 첫 페이지면 null 이다
 * @param size 클라이언트가 준 값. 범위를 벗어나면 잘린다
 */
public record MyCommentListQuery(Long authorId, MyCommentCursor cursor, int size) {

  public MyCommentListQuery {
    size = clampSize(size);
  }

  /**
   * 범위를 벗어난 {@code size} 는 거절하지 않고 자른다.
   *
   * <p>{@link CommentListQuery} 와 같은 판단이고, 기본 20 · 최대 50 도 그 상수를 그대로 쓴다 — 같은 패키지에 세 번째 사본을 두면 API
   * 컨벤션이 정한 한 값이 여러 곳에서 관리된다.
   */
  private static int clampSize(int size) {
    if (size < 1) {
      return CommentListQuery.DEFAULT_SIZE;
    }
    return Math.min(size, CommentListQuery.MAX_SIZE);
  }

  public boolean hasCursor() {
    return cursor != null;
  }
}
