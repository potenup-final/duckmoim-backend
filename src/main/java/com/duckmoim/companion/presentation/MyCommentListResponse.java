package com.duckmoim.companion.presentation;

import com.duckmoim.companion.domain.MyCommentCursor;
import com.duckmoim.companion.service.MyCommentSlice;
import java.util.List;

/**
 * 내 댓글 내역 응답 (CM-16 · AU-10).
 *
 * <p>커서 페이지네이션 응답은 {@code items} · {@code nextCursor} · {@code hasNext} 셋이다 (API-컨벤션.md 「공통 응답
 * 형식」). 성공 응답을 {@code data} 로 감싸지 않는다.
 *
 * <p><b>{@code items} 는 {@code size} 만큼 온다.</b> {@link CommentListResponse} 가 적게 올 수 있다고 밝힌 것은
 * 자리표시자를 목록에서 빼기 때문인데 (CM-11), 내 내역은 삭제·블라인드를 조회 조건이 먼저 걸러 페이지가 깎이지 않는다.
 */
public record MyCommentListResponse(
    List<MyCommentItemResponse> items, String nextCursor, boolean hasNext) {

  static MyCommentListResponse of(List<MyCommentItemResponse> items, MyCommentSlice slice) {
    return new MyCommentListResponse(items, encoded(slice.nextCursor()), slice.hasNext());
  }

  private static String encoded(MyCommentCursor cursor) {
    return cursor == null ? null : cursor.encode();
  }
}
