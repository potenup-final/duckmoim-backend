package com.duckmoim.catalog.presentation;

import com.duckmoim.catalog.service.EventIngestResult;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 적재 결과 (EV-04 · API-설계 「2-8. 적재 (Ingest)」).
 *
 * <p>러너에서 운영 DB 에 붙지 않기로 했으므로(D-8) <b>이 응답이 적재를 확인할 유일한 창이다.</b> 크롤러가 이 값을 Actions 실행 요약에 남긴다.
 */
public record EventIngestResponse(
    @Schema(
            description =
                "적재 후 누적 행수. events.json 건수와 같아지지 않는다 — 사라진 행사를 지우지 않으므로 단조 증가한다. "
                    + "두 번 돌려 이 값이 늘지 않는 것이 EV-03 의 멱등성 판정이다")
        long total,
    @Schema(description = "새로 생긴 행") int created,
    @Schema(description = "이미 있어 갱신된 행. created 와의 합이 요청 건수와 같아야 한다") int updated,
    @Schema(description = "기간이 남았는데 오래 안 잡혀 목록에서 빠진 행 (D-7)") long hidden) {

  static EventIngestResponse from(EventIngestResult result) {
    return new EventIngestResponse(
        result.total(), result.created(), result.updated(), result.hidden());
  }
}
