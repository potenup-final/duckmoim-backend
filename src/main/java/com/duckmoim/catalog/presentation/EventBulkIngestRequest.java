package com.duckmoim.catalog.presentation;

import com.duckmoim.catalog.service.EventIngestCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

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

  EventIngestCommand toCommand() {
    return new EventIngestCommand(events.stream().map(EventIngestItemRequest::toItem).toList());
  }
}
