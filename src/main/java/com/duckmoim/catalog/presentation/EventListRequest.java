package com.duckmoim.catalog.presentation;

import com.duckmoim.catalog.domain.EventCursor;
import com.duckmoim.catalog.domain.EventKind;
import com.duckmoim.catalog.domain.EventQuery;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.common.exception.CommonErrorCode;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 행사 목록 조회 요청 (API 설계 2-3).
 *
 * <p>필드가 전부 선택이다. 값을 주지 않으면 그 조건으로 거르지 않는다.
 */
public record EventListRequest(
    EventKind kind,
    Long regionId,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
    String keyword,
    String cursor,
    Integer size) {

  /**
   * 요청 DTO 를 조회 조건으로 옮긴다.
   *
   * <p>커서 판독이 여기서 일어난다. 실패는 {@code INVALID_INPUT} 400 이다 (API 설계 4장 · API 컨벤션 236줄). 클라이언트가 커서를
   * 해석하지 않기로 한 이상, 망가진 커서는 서버 잘못이 아니라 잘못된 요청이다.
   */
  EventQuery toQuery() {
    return new EventQuery(
        kind,
        regionId,
        dateFrom,
        dateTo,
        keyword,
        toCursor(),
        size == null ? EventQuery.DEFAULT_SIZE : size);
  }

  private EventCursor toCursor() {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }

    try {
      return EventCursor.decode(cursor);
    } catch (IllegalArgumentException e) {
      // 전역 예외 처리(STAR-30)가 머지됐으므로 예고한 대로 BusinessException 으로 옮긴다.
      // ResponseStatusException 을 그대로 두면 안 된다 — STAR-30 은 상태 코드를 읽어
      // 봉투로 바꾸는 방식이 아니라 **명시 핸들러 방식**으로 들어왔다. 목록에 없는 예외는
      // 전부 INTERNAL_ERROR 500 이라, 망가진 커서가 400 이 아니라 500 으로 나갔다.
      throw new BusinessException(CommonErrorCode.INVALID_INPUT);
    }
  }
}
