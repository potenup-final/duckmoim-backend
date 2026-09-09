package com.duckmoim.catalog.presentation;

import com.duckmoim.catalog.service.EventIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 크롤러가 부르는 적재 경로 (EV-03 · EV-04).
 *
 * <p><b>{@code /admin} 아래가 아니다</b> (D-11). 백오피스와 인가 방식이 달라서다 — 저쪽은 카카오로 로그인한 회원번호를 {@code
 * AdminAccount} 와 대조하고, 이쪽은 정적 키를 본다. 한 접두어에 인가 방식을 둘 두면 {@code /admin/**} 을 한 줄로 덮고 있는 규칙에 예외를 뚫어야
 * 하고, 경로 규칙은 순서 의존이라 나중에 순서가 바뀌면 백오피스가 열린다.
 *
 * <p>{@code admin} 패키지가 아니라 {@code catalog} 에 사는 이유도 같은 결이다. 이 경로가 쓰는 애그리게이트가 {@code Event} 라,
 * admin 에 두면 그 패키지가 catalog 도메인을 알아야 한다.
 *
 * <p><b>{@code AuthUser} 를 받지 않는다.</b> 부르는 쪽이 사람이 아니다. 인가는 {@code SecurityConfig} 의 {@code
 * hasAuthority(MACHINE)} 이 관문에서 끝내므로 컨트롤러가 다시 확인하지 않는다.
 */
@Tag(name = "적재", description = "크롤러가 부르는 행사 적재")
@RestController
@RequestMapping("/api/v1/ingest/events")
@RequiredArgsConstructor
public class EventIngestController {

  private final EventIngestService eventIngestService;

  /**
   * 행사를 외부 식별자 기준으로 upsert 한다 (EV-03).
   *
   * <p>성공도 200 이다. 201 을 쓰지 않는다 (API 컨벤션 「Status Code 규칙」) — 이 요청은 생성과 갱신이 섞여 있어 어느 쪽도 대표하지 못한다.
   *
   * <p><b>부분 성공을 만들지 않는다.</b> service 의 트랜잭션 하나로 처리해서, 절반만 들어간 상태로 끝나지 않는다.
   */
  @Operation(
      summary = "행사 벌크 적재",
      description = "externalId 가 있으면 갱신하고 없으면 만든다. 재실행해도 중복이 생기지 않는다.")
  @PostMapping("/bulk")
  public EventIngestResponse ingest(@Valid @RequestBody EventBulkIngestRequest request) {
    return EventIngestResponse.from(eventIngestService.ingest(request.toCommand()));
  }
}
