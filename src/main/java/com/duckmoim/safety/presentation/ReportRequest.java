package com.duckmoim.safety.presentation;

import com.duckmoim.safety.domain.ReportReason;
import com.duckmoim.safety.domain.ReportTargetType;
import com.duckmoim.safety.service.ReportCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 신고 접수 요청 (화면-계약.md 「신고 (SF)」 · API-설계.md 「2-6. 신고 (Safety)」).
 *
 * <p>네 필드가 계약이고 대상 셋이 같은 모양을 쓴다 — <i>"시트는 하나를 쓰고 대상만 바뀐다 (SF-07)"</i>.
 *
 * <p><b>{@code reason} 은 enum 코드다.</b> 화면에 뜨는 한국어 라벨을 그대로 보내지 않는다. 값 자체가 enum 에 없으면 역직렬화가 먼저 걸러
 * {@code INVALID_INPUT} 400 이 되고, <b>대상과의 조합</b>은 애그리게이트가 판정해 {@code REPORT_REASON_INVALID} 400 이
 * 된다. 둘은 다른 실패다.
 *
 * <p>{@code detail} 은 선택 입력이다. 길이만 본다 — 명세서가 필수 여부를 정하지 않았다.
 */
public record ReportRequest(
    @Schema(description = "신고 대상 종류", example = "COMMENT") @NotNull ReportTargetType targetType,
    @Schema(description = "신고 대상 id", example = "12") @NotNull Long targetId,
    @Schema(description = "사유 코드. 목록은 클라이언트가 갖는다 (결정 D-6)", example = "INAPPROPRIATE") @NotNull
        ReportReason reason,
    @Schema(description = "상세. 선택 입력이고 500자 이하", nullable = true)
        @Size(max = 500, message = "상세는 500자 이하여야 합니다.")
        String detail) {

  ReportCommand toCommand(Long reporterId) {
    return new ReportCommand(reporterId, targetType, targetId, reason, detail);
  }
}
