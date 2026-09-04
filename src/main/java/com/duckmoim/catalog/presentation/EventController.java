package com.duckmoim.catalog.presentation;

import com.duckmoim.catalog.service.EventQueryService;
import com.duckmoim.catalog.service.EventSlice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "행사", description = "행사 조회")
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

  private final EventQueryService eventQueryService;

  /**
   * 행사 목록을 조회한다 (EV-05 · EV-06).
   *
   * <p>인증이 필요 없다 (API 설계 2-3 · {@code PUBLIC}). 첫 페이지는 Next 서버가 ISR 빌드·재검증 때 부르고, 2페이지 이후만 브라우저가
   * 스크롤로 부른다.
   */
  @Operation(summary = "행사 목록 조회", description = "종류·지역·행사일·키워드로 거르고 커서로 페이지를 넘긴다.")
  @GetMapping
  public EventListResponse getEvents(EventListRequest request) {
    EventSlice slice = eventQueryService.findEvents(request.toQuery());

    return EventListResponse.from(slice);
  }
}
