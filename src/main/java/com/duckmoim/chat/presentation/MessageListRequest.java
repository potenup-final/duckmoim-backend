package com.duckmoim.chat.presentation;

import com.duckmoim.chat.domain.MessageCursor;
import com.duckmoim.chat.domain.MessageListQuery;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.common.exception.CommonErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 메시지 목록 요청 파라미터 (API-컨벤션.md 「공통 응답 형식」의 {@code cursor} · {@code size}).
 *
 * <p>{@code size} 는 검증하지 않는다 — 범위를 벗어나면 {@link MessageListQuery} 가 자른다. 목록 크기는 클라이언트의 편의값이지 계약 위반이
 * 아니다 (API-설계.md 「검증 상한」).
 */
public record MessageListRequest(
    @Schema(description = "이전 응답의 nextCursor. 첫 페이지는 생략한다", nullable = true) String cursor,
    @Schema(description = "기본 30, 최대 50") Integer size) {

  MessageListQuery toQuery(Long roomId) {
    return new MessageListQuery(roomId, decodedCursor(), size == null ? 0 : size);
  }

  /**
   * 판독할 수 없는 커서는 {@code INVALID_INPUT} 400 이다.
   *
   * <p>API-컨벤션.md 「Validation 규칙」이 <i>"커서 디코딩 실패는 INVALID_INPUT 으로 400 을 반환한다"</i> 고 못박았다.
   */
  private MessageCursor decodedCursor() {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }

    try {
      return MessageCursor.decode(cursor);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(CommonErrorCode.INVALID_INPUT);
    }
  }
}
