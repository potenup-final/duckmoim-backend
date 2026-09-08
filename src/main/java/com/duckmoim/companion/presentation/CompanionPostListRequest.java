package com.duckmoim.companion.presentation;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.common.exception.CommonErrorCode;
import com.duckmoim.companion.domain.PostCursor;
import com.duckmoim.companion.domain.PostListQuery;
import com.duckmoim.companion.domain.PostStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 모집글 목록 요청 파라미터 (PO-08).
 *
 * <p>{@code size} 는 검증하지 않는다 — 범위를 벗어나면 {@link PostListQuery} 가 자른다. 목록 크기는 클라이언트의 편의값이지 계약 위반이
 * 아니다.
 */
public record CompanionPostListRequest(
    @Schema(description = "OPEN 하나만 받는다. 생략하면 전체다", allowableValues = "OPEN", nullable = true)
        String status,
    @Schema(description = "이전 응답의 nextCursor. 첫 페이지는 생략한다", nullable = true) String cursor,
    @Schema(description = "기본 20, 최대 50") Integer size) {

  PostListQuery toQuery() {
    return new PostListQuery(decodedStatus(), decodedCursor(), size == null ? 0 : size);
  }

  /**
   * <b>{@code OPEN} 하나만 받는다.</b> 생략하면 전체다 — API-설계.md 「2-4. 모집글 (Companion)」이 <i>"목록의 {@code
   * ?status=} 는 {@code OPEN} 하나만 받는다. 생략하면 전체다"</i> 로 정했고, {@code CLOSED} 만 보는 화면이 없어 값을 늘리지 않았다.
   *
   * <p><b>그래서 enum 으로 바인딩받지 않는다.</b> {@code PostStatus} 로 받으면 {@code ?status=CLOSED} 가 조용히 통과해서 계약에
   * 없는 목록이 나간다. 열거값이 아니라 <b>허용 목록</b>을 지키는 일이라 문자열로 받아 여기서 판정한다.
   *
   * <p>허용되지 않은 값은 {@code INVALID_INPUT} 400 이다 (API-설계.md 「4. 에러 코드」).
   */
  private PostStatus decodedStatus() {
    if (status == null || status.isBlank()) {
      return null;
    }
    if (!PostStatus.OPEN.name().equals(status)) {
      throw new BusinessException(CommonErrorCode.INVALID_INPUT);
    }

    return PostStatus.OPEN;
  }

  /**
   * 판독할 수 없는 커서는 {@code INVALID_INPUT} 400 이다.
   *
   * <p>API-컨벤션.md 「Validation 규칙」이 <i>"커서 디코딩 실패는 INVALID_INPUT 으로 400 을 반환한다"</i> 고 못박았다. {@code
   * EventListRequest} · {@code CommentListRequest} 가 같은 방식이다.
   */
  private PostCursor decodedCursor() {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }

    try {
      return PostCursor.decode(cursor);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(CommonErrorCode.INVALID_INPUT);
    }
  }
}
