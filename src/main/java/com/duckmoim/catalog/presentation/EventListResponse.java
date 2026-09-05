package com.duckmoim.catalog.presentation;

import com.duckmoim.catalog.domain.EventCursor;
import com.duckmoim.catalog.service.EventSlice;
import java.util.List;

/**
 * 커서 페이지네이션 목록 응답 (API 컨벤션 · 공통 응답 형식).
 *
 * <p>{@code items} · {@code nextCursor} · {@code hasNext} 세 필드다. 성공 응답을 {@code data} 로 감싸지 않는다. 마지막
 * 페이지에서 {@code nextCursor} 는 null 이고 {@code hasNext} 는 false 다.
 */
public record EventListResponse(List<EventItemResponse> items, String nextCursor, boolean hasNext) {

  static EventListResponse from(EventSlice slice) {
    EventCursor nextCursor = slice.nextCursor();

    return new EventListResponse(
        slice.events().stream().map(EventItemResponse::from).toList(),
        nextCursor == null ? null : nextCursor.encode(),
        slice.hasNext());
  }
}
