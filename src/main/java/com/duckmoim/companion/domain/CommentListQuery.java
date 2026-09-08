package com.duckmoim.companion.domain;

/**
 * 댓글 목록의 조회 조건 (CM-06 · CM-07).
 *
 * <p>거르는 조건이 {@code postId} 하나뿐이다. 상태로 거르지 않는다 — 삭제·블라인드 댓글도 자리표시자로 목록에 남아야 하고 (CM-08 · CM-11), 어느
 * 것을 뺄지는 <b>하위 대댓글이 있는지</b>로 갈려 SQL 조건으로 표현되지 않는다.
 *
 * @param cursor 첫 페이지면 null 이다
 * @param size 클라이언트가 준 값. 범위를 벗어나면 잘린다
 */
public record CommentListQuery(Long postId, CommentCursor cursor, int size) {

  public static final int DEFAULT_SIZE = 20;
  public static final int MAX_SIZE = 50;

  private static final int MIN_SIZE = 1;

  public CommentListQuery {
    size = clampSize(size);
  }

  /**
   * 범위를 벗어난 {@code size} 는 거절하지 않고 자른다.
   *
   * <p>{@code EventQuery} 와 같은 판단이다. 목록 크기는 클라이언트의 편의값이지 계약 위반이 아니라서, 400 으로 돌려보내면 화면이 이유 없이 비는 쪽이
   * 더 나쁘다. 기본 20 · 최대 50 은 API 컨벤션이 정했다.
   */
  private static int clampSize(int size) {
    if (size < MIN_SIZE) {
      return DEFAULT_SIZE;
    }
    return Math.min(size, MAX_SIZE);
  }

  public boolean hasCursor() {
    return cursor != null;
  }
}
