package com.duckmoim.catalog.service;

import com.duckmoim.catalog.domain.Event;
import com.duckmoim.catalog.domain.EventCursor;
import java.util.List;
import java.util.Map;

/**
 * 행사 목록 한 페이지 (EV-06).
 *
 * <p>service 가 {@code Slice} · {@code Pageable} 을 시그니처에 노출하지 않기 위한 결과 객체다 (아키텍처 컨벤션 · Service 작성
 * 규칙). presentation 이 이것을 응답 DTO 로 조립한다.
 *
 * <p>{@code nextCursor} 는 <b>마지막 항목의 위치</b>다. 순번이 아니므로 그 사이 행사가 추가·삭제돼도 다음 페이지가 어긋나지 않는다 (API 설계
 * 2-3).
 */
public record EventSlice(List<EventSummary> events, EventCursor nextCursor, boolean hasNext) {

  /**
   * 저장소가 {@code size + 1} 건까지 읽어온 것을 한 페이지로 자른다.
   *
   * <p>한 건이 더 왔으면 다음 페이지가 있다는 뜻이고, 그 여분은 응답에 넣지 않는다.
   *
   * <p><b>커서는 자른 뒤의 마지막 항목에서 뽑는다.</b> 잘라내기 전 목록에서 뽑으면 여분 한 건을 가리키게 되어, 다음 페이지가 그 행사를 건너뛴다 — EV-06 이
   * 금지한 누락이 정확히 이 실수에서 나온다.
   */
  static EventSlice of(List<Event> found, int size, Map<Long, String> districtByRegionId) {
    boolean hasNext = found.size() > size;
    List<Event> page = hasNext ? found.subList(0, size) : found;

    if (page.isEmpty()) {
      return new EventSlice(List.of(), null, false);
    }

    Event last = page.get(page.size() - 1);
    EventCursor nextCursor = hasNext ? EventCursor.of(last.getEndsOn(), last.getId()) : null;

    List<EventSummary> events =
        page.stream()
            .map(event -> EventSummary.from(event, districtByRegionId.get(event.getRegionId())))
            .toList();

    return new EventSlice(events, nextCursor, hasNext);
  }
}
