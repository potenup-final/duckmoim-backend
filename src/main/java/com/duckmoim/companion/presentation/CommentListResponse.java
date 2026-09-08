package com.duckmoim.companion.presentation;

import com.duckmoim.companion.domain.CommentCursor;
import com.duckmoim.companion.service.CommentSlice;
import java.util.List;

/**
 * 댓글 목록 응답 (CM-06 · CM-07).
 *
 * <p>커서 페이지네이션 응답은 {@code items} · {@code nextCursor} · {@code hasNext} 셋이다 (API-컨벤션.md 「공통 응답
 * 형식」). 성공 응답을 {@code data} 로 감싸지 않는다.
 *
 * <p><b>{@code items} 가 {@code size} 보다 적을 수 있다.</b> 하위 대댓글 없는 자리표시자가 목록에서 빠지기 때문이다 (CM-11). 커서는
 * 걸러지기 전을 기준으로 움직이므로 개수가 아니라 {@code hasNext} 를 봐야 한다.
 */
public record CommentListResponse(
    List<CommentItemResponse> items, String nextCursor, boolean hasNext) {

  static CommentListResponse of(List<CommentItemResponse> items, CommentSlice slice) {
    return new CommentListResponse(items, encoded(slice.nextCursor()), slice.hasNext());
  }

  private static String encoded(CommentCursor cursor) {
    return cursor == null ? null : cursor.encode();
  }
}
