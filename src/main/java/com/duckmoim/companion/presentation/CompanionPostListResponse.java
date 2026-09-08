package com.duckmoim.companion.presentation;

import com.duckmoim.companion.domain.PostCursor;
import com.duckmoim.companion.service.PostSlice;
import java.util.List;

/**
 * 모집글 목록 응답 (PO-08).
 *
 * <p>커서 페이지네이션 응답은 {@code items} · {@code nextCursor} · {@code hasNext} 셋이다 (API-컨벤션.md 「공통 응답
 * 형식」). 성공 응답을 {@code data} 로 감싸지 않고, 총 건수를 담지 않는다.
 *
 * <p>마지막 페이지에서 {@code nextCursor} 는 null 이고 {@code hasNext} 는 false 다.
 */
public record CompanionPostListResponse(
    List<CompanionPostItemResponse> items, String nextCursor, boolean hasNext) {

  static CompanionPostListResponse from(PostSlice slice) {
    return new CompanionPostListResponse(
        slice.posts().stream().map(CompanionPostItemResponse::from).toList(),
        encoded(slice.nextCursor()),
        slice.hasNext());
  }

  private static String encoded(PostCursor cursor) {
    return cursor == null ? null : cursor.encode();
  }
}
