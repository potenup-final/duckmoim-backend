package com.duckmoim.safety.presentation;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.common.exception.CommonErrorCode;
import com.duckmoim.safety.domain.ReportCursor;
import com.duckmoim.safety.domain.ReportListQuery;
import com.duckmoim.safety.domain.ReportStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 백오피스 신고 목록 요청 파라미터 (AD-02).
 *
 * <p>거르는 것은 처리 상태 하나다. <b>안 주면 전량</b>이고, 그것이 AD-02 의 검증 기준(「접수 건 전량 조회」)이다.
 *
 * <p>{@code size} 는 검증하지 않는다 — 범위를 벗어나면 {@link ReportListQuery} 가 자른다. 목록 크기는 클라이언트의 편의값이지 계약 위반이
 * 아니다.
 */
public record AdminReportListRequest(
    @Schema(description = "처리 상태 필터. 생략하면 전량이다", nullable = true) ReportStatus status,
    @Schema(description = "이전 응답의 nextCursor. 첫 페이지는 생략한다", nullable = true) String cursor,
    @Schema(description = "기본 20, 최대 50") Integer size) {

  ReportListQuery toQuery() {
    return new ReportListQuery(status, decodedCursor(), size == null ? 0 : size);
  }

  /**
   * 판독할 수 없는 커서는 {@code INVALID_INPUT} 400 이다.
   *
   * <p>API-컨벤션.md 「Validation 규칙」이 <i>"커서 디코딩 실패는 INVALID_INPUT 으로 400 을 반환한다"</i> 고 못박았다.
   */
  private ReportCursor decodedCursor() {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }

    try {
      return ReportCursor.decode(cursor);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(CommonErrorCode.INVALID_INPUT);
    }
  }
}
