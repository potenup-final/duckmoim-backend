package com.duckmoim.catalog.presentation;

import com.duckmoim.catalog.domain.EventCursor;
import com.duckmoim.catalog.domain.EventKind;
import com.duckmoim.catalog.domain.EventQuery;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
      // ResponseStatusException 을 쓰는 이유 — 전역 예외 처리(STAR-30) 가 아직 develop 에
      // 없다. 이 예외는 지금도 400 을 내고, 그 PR 이 머지되면 핸들러가 상태 코드를 보고
      // INVALID_INPUT 봉투로 바꿔준다. 그때 이 자리를 BusinessException 으로 옮긴다.
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "커서를 판독할 수 없습니다.", e);
    }
  }
}
