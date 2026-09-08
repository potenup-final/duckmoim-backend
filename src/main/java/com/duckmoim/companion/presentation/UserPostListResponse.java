package com.duckmoim.companion.presentation;

import com.duckmoim.companion.domain.UserPostCursor;
import com.duckmoim.companion.service.UserPostSlice;
import java.util.List;

/**
 * 유저가 쓴 모집글 목록 응답 (AU-09 · AU-10).
 *
 * <p>봉투가 {@code items} · {@code nextCursor} · {@code hasNext} 셋이다 (API 컨벤션 공통 규칙). 항목은 {@link
 * CompanionPostItemResponse} 를 그대로 쓴다 — <b>프론트가 모집글 카드 파서를 하나만 쓴다.</b>
 */
public record UserPostListResponse(
    List<CompanionPostItemResponse> items, String nextCursor, boolean hasNext) {

  static UserPostListResponse from(UserPostSlice slice) {
    return new UserPostListResponse(
        slice.posts().stream().map(CompanionPostItemResponse::from).toList(),
        encoded(slice.nextCursor()),
        slice.hasNext());
  }

  private static String encoded(UserPostCursor cursor) {
    return cursor == null ? null : cursor.encode();
  }
}
