package com.duckmoim.companion.domain;

/**
 * 유저가 쓴 모집글 목록의 조회 조건 (AU-09 · AU-10).
 *
 * <p><b>{@code hostId} 를 부르는 쪽이 정한다.</b> 내 내역({@code /users/me/posts})은 인증 주체에서, 남의 것({@code
 * /users/&#123;userId&#125;/posts})은 경로 변수에서 온다. <b>조회는 그 둘을 구별하지 않는다</b> — 모집글에 비공개 개념이 없어(상태가
 * {@code OPEN}·{@code CLOSED} 둘이고 삭제도 없다 · 결정 D-3) 지금은 같은 결과다.
 *
 * <p><b>{@code status} 를 받지 않는다.</b> 목록(PO-08)은 「모집중 / 전체」 필터가 화면에 있지만 프로필 탭에는 그 요구가 없다. 마감된 글도 내역에
 * 남아야 한다.
 *
 * <p>크기 제한은 {@link PostListQuery} 의 상수를 그대로 쓴다. 같은 표를 읽는 목록이 셋째 상수 집합을 갖게 하지 않는다 ({@link
 * MyCommentListQuery} 가 {@code CommentListQuery} 를 재사용한 것과 같다).
 */
public record UserPostListQuery(Long hostId, UserPostCursor cursor, int size) {

  public UserPostListQuery {
    size = clampSize(size);
  }

  private static int clampSize(int size) {
    if (size < 1) {
      return PostListQuery.DEFAULT_SIZE;
    }
    return Math.min(size, PostListQuery.MAX_SIZE);
  }

  public boolean hasCursor() {
    return cursor != null;
  }
}
