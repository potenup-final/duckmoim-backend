package com.duckmoim.companion.presentation;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.common.exception.CommonErrorCode;
import com.duckmoim.companion.domain.MyCommentCursor;
import com.duckmoim.companion.domain.MyCommentListQuery;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 내 댓글 내역 요청 파라미터 (API-컨벤션.md 「공통 응답 형식」의 {@code cursor} · {@code size}).
 *
 * <p><b>작성자를 파라미터로 받지 않는다.</b> {@link #toQuery(Long)} 가 인증 주체에서 받는다 — 요청에서 받으면 남의 내역을 보는 문이 생긴다.
 *
 * <p>{@code size} 는 검증하지 않는다 — 범위를 벗어나면 {@link MyCommentListQuery} 가 자른다. 목록 크기는 클라이언트의 편의값이지 계약
 * 위반이 아니다.
 */
public record MyCommentListRequest(
    @Schema(description = "이전 응답의 nextCursor. 첫 페이지는 생략한다", nullable = true) String cursor,
    @Schema(description = "기본 20, 최대 50") Integer size) {

  MyCommentListQuery toQuery(Long authorId) {
    return new MyCommentListQuery(authorId, decodedCursor(), size == null ? 0 : size);
  }

  /**
   * 판독할 수 없는 커서는 {@code INVALID_INPUT} 400 이다.
   *
   * <p>API-컨벤션.md 「Validation 규칙」이 <i>"커서 디코딩 실패는 INVALID_INPUT 으로 400 을 반환한다"</i> 고 못박았다.
   */
  private MyCommentCursor decodedCursor() {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }

    try {
      return MyCommentCursor.decode(cursor);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(CommonErrorCode.INVALID_INPUT);
    }
  }
}
