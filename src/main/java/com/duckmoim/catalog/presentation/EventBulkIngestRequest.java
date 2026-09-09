package com.duckmoim.catalog.presentation;

import com.duckmoim.catalog.service.EventIngestCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Objects;

/**
 * 크롤러가 한 번에 보내는 배치 (EV-03).
 *
 * <p><b>상한을 둔다.</b> 지금 한 번에 오는 것이 200여 건이고 콘서트가 붙어 늘어나는 중인데, 상한이 없으면 요청 하나가 트랜잭션을 얼마든지 길게 잡을 수 있다.
 * 1000 은 현재치의 다섯 배 가까이라 정상 실행이 걸리지 않고, 넘으면 크롤러가 페이지를 나눠 보내면 된다 — 벌크 계약이 부분 갱신이라(D-7) 나눠 보내도 성립한다.
 */
public record EventBulkIngestRequest(
    @Schema(description = "적재할 행사들. 외부 식별자 기준 upsert 다")
        @NotEmpty(message = "적재할 행사가 없습니다.")
        @Size(max = 1000, message = "한 번에 1000건까지 보낼 수 있습니다.")
        @Valid
        List<EventIngestItemRequest> events) {

  /**
   * 한 배치 안에서 외부 식별자가 겹치지 않는지 본다.
   *
   * <p>겹친 채로 통과하면 적재가 <b>기존 DB 에 있는지만</b> 보고 판정하므로 둘 다 새 행으로 만들어 유니크 키를 위반한다. {@code
   * EventIngestService.ingest} 가 트랜잭션 하나라 중복 한 쌍 때문에 그 배치가 통째로 롤백되고, 잡이 하루 한 번이라 다음 날까지 DB 가 갱신되지
   * 않는다.
   *
   * <p>필수 필드와 같은 자리에서 400 으로 되돌린다 — 크롤러가 자기 출력을 자기가 검사하게 두지 않는다 (EV-04). 같은 행사가 한 요청에 두 번 실린 것은
   * 크롤러의 버그라, 받아주면 그 버그가 조용히 묻힌다.
   *
   * <p>{@code null} 은 여기서 세지 않는다. 빈 목록은 {@code @NotEmpty} 가, 빈 식별자는 {@code @NotBlank} 가 각자 보고한다.
   */
  @AssertTrue(message = "외부 식별자가 중복된 행사가 있습니다.")
  public boolean isExternalIdUnique() {
    if (events == null) {
      return true;
    }
    List<String> externalIds =
        events.stream()
            .filter(Objects::nonNull)
            .map(EventIngestItemRequest::externalId)
            .filter(Objects::nonNull)
            .toList();

    return externalIds.size() == externalIds.stream().distinct().count();
  }

  EventIngestCommand toCommand() {
    return new EventIngestCommand(events.stream().map(EventIngestItemRequest::toItem).toList());
  }
}
