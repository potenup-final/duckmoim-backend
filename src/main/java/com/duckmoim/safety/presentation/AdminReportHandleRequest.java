package com.duckmoim.safety.presentation;

import com.duckmoim.safety.domain.ReportResult;
import com.duckmoim.safety.domain.ReportStatus;
import com.duckmoim.safety.service.ReportHandleCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 신고 처리 요청 (AD-03 · 화면-계약.md 「신고 처리」).
 *
 * <p><b>{@code result} 를 필수로 두지 않는다.</b> {@code PROCESSING} 으로 잡을 때는 종결이 아니라 남길 결과가 없다. {@code
 * RESOLVED} 인데 비어 있으면 어떻게 할지는 도메인이 판정하지 않고 그대로 null 로 남는다 — 화면 계약이 종결 사유를 필수로 적지 않았고, 없는 규칙을 여기서
 * 지어내면 계약보다 좁아진다.
 *
 * <p><b>{@code memo} 는 계약에 없던 필드다.</b> 화면 계약의 {@code result} 가 사람이 적는 문자열이었는데 STAR-78 이 세는 축과 문장으로
 * 나눴다. 길이는 신고 {@code detail} 과 같은 500 으로 맞췄다.
 */
public record AdminReportHandleRequest(
    @Schema(description = "옮길 상태") @NotNull ReportStatus status,
    @Schema(description = "종결 사유. RESOLVED 일 때만 쓰인다", nullable = true) ReportResult result,
    @Schema(description = "관리자가 남기는 판단 맥락", nullable = true) @Size(max = 500) String memo) {

  ReportHandleCommand toCommand(Long reportId, Long adminUserId) {
    return new ReportHandleCommand(reportId, status, result, memo, adminUserId);
  }
}
