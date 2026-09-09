package com.duckmoim.safety.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.safety.exception.ReportErrorCode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 신고 처리의 도메인 규칙 (AD-03).
 *
 * <p>전이표 자체는 {@link ReportStatusTest} 가 아홉 조합으로 덮는다. 여기서는 <b>전이가 무엇을 남기는지</b>와 <b>거부가 어느 코드로
 * 갈리는지</b>를 본다.
 */
class ReportHandleTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 9, 1, 0);

  private static final long ADMIN_ID = 6L;
  private static final long REPORTER_ID = 2L;
  private static final long TARGET_ID = 42L;

  @DisplayName("PENDING 을 PROCESSING 으로 잡는다.")
  @Test
  void handleToProcessing() {
    Report report = report();

    report.handle(ReportStatus.PROCESSING, null, null, ADMIN_ID, NOW);

    assertThat(report.getStatus()).isEqualTo(ReportStatus.PROCESSING);
    assertThat(report.getHandledBy()).isEqualTo(ADMIN_ID);
    assertThat(report.getHandledAt()).isEqualTo(NOW);
  }

  /** 「내가 잡았다」는 표시이지 종결이 아니다. 관리자가 넷이고 창구가 하나뿐이라 두는 상태다. */
  @DisplayName("PROCESSING 에는 결과를 남기지 않는다.")
  @Test
  void handleToProcessingKeepsResultEmpty() {
    Report report = report();

    report.handle(ReportStatus.PROCESSING, ReportResult.NO_ACTION, "적어도 무시된다", ADMIN_ID, NOW);

    assertThat(report.getResult()).isNull();
    assertThat(report.getMemo()).isNull();
  }

  @DisplayName("PROCESSING 을 RESOLVED 로 처리하면 결과가 남는다.")
  @Test
  void handleToResolved() {
    Report report = report();
    report.handle(ReportStatus.PROCESSING, null, null, ADMIN_ID, NOW);

    report.handle(
        ReportStatus.RESOLVED, ReportResult.USER_SANCTIONED, "3일 정지", ADMIN_ID, NOW.plusHours(1));

    assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED);
    assertThat(report.getResult()).isEqualTo(ReportResult.USER_SANCTIONED);
    assertThat(report.getMemo()).isEqualTo("3일 정지");
    assertThat(report.getHandledAt()).isEqualTo(NOW.plusHours(1));
  }

  /** 「볼 것도 없이 끝나는 건까지 두 번 누르게 할 이유가 없다」 (도메인 6장). */
  @DisplayName("PENDING 에서 RESOLVED 로 바로 갈 수 있다.")
  @Test
  void handlePendingToResolved() {
    Report report = report();

    report.handle(ReportStatus.RESOLVED, ReportResult.NO_ACTION, null, ADMIN_ID, NOW);

    assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED);
    assertThat(report.getResult()).isEqualTo(ReportResult.NO_ACTION);
  }

  @DisplayName("메모는 없어도 된다.")
  @Test
  void handleWithoutMemo() {
    Report report = report();

    report.handle(ReportStatus.RESOLVED, ReportResult.NO_ACTION, null, ADMIN_ID, NOW);

    assertThat(report.getMemo()).isNull();
  }

  /** AD-03 의 검증 기준이 걸린 자리다 — 「RESOLVED 건 재처리 시 409」. */
  @DisplayName("이미 종결된 신고를 다시 처리하면 REPORT_ALREADY_HANDLED 다.")
  @Test
  void handle_isAlreadyResolved() {
    Report report = report();
    report.handle(ReportStatus.RESOLVED, ReportResult.NO_ACTION, null, ADMIN_ID, NOW);

    assertThatThrownBy(
            () -> report.handle(ReportStatus.RESOLVED, ReportResult.NO_ACTION, null, ADMIN_ID, NOW))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(ReportErrorCode.REPORT_ALREADY_HANDLED);
  }

  /** 아직 종결되지 않았으므로 「이미 처리된 신고입니다」가 아니다. 코드가 갈리는 이유가 이것이다. */
  @DisplayName("되돌리는 전이는 REPORT_TRANSITION_NOT_ALLOWED 다.")
  @Test
  void handle_movesBackward() {
    Report report = report();
    report.handle(ReportStatus.PROCESSING, null, null, ADMIN_ID, NOW);

    assertThatThrownBy(() -> report.handle(ReportStatus.PENDING, null, null, ADMIN_ID, NOW))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(ReportErrorCode.REPORT_TRANSITION_NOT_ALLOWED);
  }

  /** 아무것도 바꾸지 않는 요청을 성공으로 답하면 처리 시각과 처리자만 덮어써진다. */
  @DisplayName("이미 누가 잡은 건을 또 잡을 수 없다.")
  @Test
  void handle_isAlreadyProcessing() {
    Report report = report();
    report.handle(ReportStatus.PROCESSING, null, null, ADMIN_ID, NOW);

    assertThatThrownBy(() -> report.handle(ReportStatus.PROCESSING, null, null, 7L, NOW))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(ReportErrorCode.REPORT_TRANSITION_NOT_ALLOWED);
  }

  @DisplayName("거부된 전이는 아무것도 바꾸지 않는다.")
  @Test
  void handle_rejectedKeepsEverything() {
    Report report = report();
    report.handle(ReportStatus.PROCESSING, null, null, ADMIN_ID, NOW);

    assertThatThrownBy(
            () -> report.handle(ReportStatus.PROCESSING, null, null, 7L, NOW.plusDays(1)))
        .isInstanceOf(BusinessException.class);

    assertThat(report.getHandledBy()).isEqualTo(ADMIN_ID);
    assertThat(report.getHandledAt()).isEqualTo(NOW);
  }

  private static Report report() {
    return Report.of(
        REPORTER_ID, ReportTargetType.USER, TARGET_ID, ReportReason.NO_SHOW, "연락이 끊겼습니다.");
  }
}
