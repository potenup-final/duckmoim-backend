package com.duckmoim.catalog.presentation;

import com.duckmoim.catalog.service.EventQueryService;
import com.duckmoim.catalog.service.EventSlice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

  /**
   * 행사 한 건을 조회한다 (EV-07).
   *
   * <p><b>경로 변수는 외부 식별자다</b> ({@code pg_8709}). 숫자 PK 가 아니다 — 도메인 4장이 「URL 은 외부 식별자로 관리한다」 이고 프론트
   * 주소 {@code duckmoim.com/e/pg_8709} 가 이미 색인돼 있다 (API 설계 2-3).
   *
   * <p>지금 이것을 부르는 화면이 없다. 프론트는 상세를 목록 응답 배열로 빌드 때 정적 생성한다 (화면 계약 「왜 목록에 거의 다 실어야 하는가」). 남기는 이유는 목록에
   * 없는 행사로 직접 들어오는 경로 — 만료된 링크, 재검증 사이에 추가된 행사 — 다.
   *
   * <p>응답 모양이 목록 항목과 같다. 프론트가 같은 화면을 두 경로로 그리는데 모양이 갈리면 렌더러가 둘이 된다.
   */
  @Operation(summary = "행사 상세 조회", description = "외부 식별자로 행사 한 건을 읽는다. 끝난 행사도 나온다.")
  @GetMapping("/{eventId}")
  public EventItemResponse getEvent(@PathVariable String eventId) {
    return EventItemResponse.from(eventQueryService.findEvent(eventId));
  }
}
