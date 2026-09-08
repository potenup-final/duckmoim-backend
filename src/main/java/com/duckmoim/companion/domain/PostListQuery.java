package com.duckmoim.companion.domain;

/**
 * 모집글 목록의 조회 조건 (PO-08).
 *
 * <p>정렬은 {@code (meetAt, id)} 오름차순 고정, 곧 <b>만남시각 임박순</b>이다. 파라미터로 받지 않는 이유는 커서가 정렬 키를 담고 있어서다 — 정렬이
 * 바뀌면 이미 발급한 커서가 무의미해진다 ({@code EventQuery} 와 같은 판단).
 *
 * <p><b>지난 글을 여기서 거르지 않는다.</b> 만남시각이 지난 글은 배치가 {@code CLOSED(MEET_TIME_PASSED)} 로 내리고 (PO-14), 도메인
 * 6장이 {@code CLOSED} 의 열람을 「가능」으로 정했다. 행사 목록이 끝난 행사를 거르는 것과 다르다 — 그쪽은 목록 자체의 성질이라고 화면 계약이 정했고,
 * 모집글에는 그런 규칙이 없다.
 *
 * @param status 거르지 않으면 {@code null} 이다. <b>{@code null} 이 「전체」다</b> — API-설계.md 「2-4. 모집글
 *     (Companion)」이 <i>"목록의 {@code ?status=} 는 {@code OPEN} 하나만 받는다. 생략하면 전체다"</i> 로 정했다. 같은 절의 표는
 *     「{@code ?status=OPEN} 기본」이라고 적었지만, 불렛이 PO-08 의 「모집중 / 전체」를 근거로 들고 있어 그쪽을 따랐다
 * @param cursor 첫 페이지면 null 이다
 * @param size 클라이언트가 준 값. 범위를 벗어나면 잘린다
 */
public record PostListQuery(PostStatus status, PostCursor cursor, int size) {

  public static final int DEFAULT_SIZE = 20;
  public static final int MAX_SIZE = 50;

  private static final int MIN_SIZE = 1;

  public PostListQuery {
    size = clampSize(size);
  }

  /**
   * 범위를 벗어난 {@code size} 는 거절하지 않고 자른다.
   *
   * <p>{@code EventQuery} · {@code CommentListQuery} 와 같은 판단이다. 목록 크기는 클라이언트의 편의값이지 계약 위반이 아니라서,
   * 400 으로 돌려보내면 화면이 이유 없이 비는 쪽이 더 나쁘다. 기본 20 · 최대 50 은 API 컨벤션이 정했다.
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

  public boolean hasStatus() {
    return status != null;
  }
}
