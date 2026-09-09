package com.duckmoim.safety.service;

import static com.duckmoim.safety.ReportFixture.aReport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.safety.domain.Report;
import com.duckmoim.safety.domain.ReportResult;
import com.duckmoim.safety.domain.ReportStatus;
import com.duckmoim.safety.domain.ReportTargetType;
import com.duckmoim.safety.exception.ReportErrorCode;
import com.duckmoim.safety.infra.ReportRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신고 처리 전이의 실행 (AD-03).
 *
 * <p>전이 규칙 자체는 {@code ReportStatusTest} 와 {@code ReportHandleTest} 가 단위로 덮는다. 여기서는 <b>저장소를 지나야 성립하는
 * 것</b>을 본다 — 저장값이 실제로 바뀌는지, 없는 신고가 404 인지, 그리고 처리 시각이 UTC 로 들어가는지.
 *
 * <p>관리자는 V11 시드의 6 번('운영자'). 신고자는 4 번('지나가던덕후').
 */
@SpringBootTest
@Transactional
class ReportHandleServiceTest {

  private static final long ADMIN_ID = 6L;
  private static final long REPORTER_ID = 4L;

  private static final AtomicLong TARGET_SEQUENCE = new AtomicLong(9000);

  @Autowired private ReportHandleService reportHandleService;
  @Autowired private ReportRepository reportRepository;
  @Autowired private JdbcTemplate jdbc;

  @DisplayName("PENDING 을 PROCESSING 으로 잡는다.")
  @Test
  void handleToProcessing() {
    long reportId = report(ReportStatus.PENDING);

    reportHandleService.handle(command(reportId, ReportStatus.PROCESSING, null, null));

    Report saved = reload(reportId);
    assertThat(saved.getStatus()).isEqualTo(ReportStatus.PROCESSING);
    assertThat(saved.getHandledBy()).isEqualTo(ADMIN_ID);
    assertThat(saved.getHandledAt()).isNotNull();
  }

  @DisplayName("PROCESSING 을 RESOLVED 로 처리하면 결과가 저장된다.")
  @Test
  void handleToResolved() {
    long reportId = report(ReportStatus.PROCESSING);

    reportHandleService.handle(
        command(reportId, ReportStatus.RESOLVED, ReportResult.USER_SANCTIONED, "3일 정지"));

    Report saved = reload(reportId);
    assertThat(saved.getStatus()).isEqualTo(ReportStatus.RESOLVED);
    assertThat(saved.getResult()).isEqualTo(ReportResult.USER_SANCTIONED);
    assertThat(saved.getMemo()).isEqualTo("3일 정지");
  }

  /** 「볼 것도 없이 끝나는 건까지 두 번 누르게 할 이유가 없다」 (도메인 6장). */
  @DisplayName("PENDING 에서 RESOLVED 로 바로 갈 수 있다.")
  @Test
  void handlePendingToResolved() {
    long reportId = report(ReportStatus.PENDING);

    reportHandleService.handle(
        command(reportId, ReportStatus.RESOLVED, ReportResult.NO_ACTION, null));

    assertThat(reload(reportId).getStatus()).isEqualTo(ReportStatus.RESOLVED);
  }

  /** AD-03 의 검증 기준이 걸린 자리다 — 「RESOLVED 건 재처리 시 409」. */
  @DisplayName("이미 종결된 신고를 다시 처리하면 REPORT_ALREADY_HANDLED 다.")
  @Test
  void handle_isAlreadyResolved() {
    long reportId = report(ReportStatus.RESOLVED);

    assertThatThrownBy(
            () ->
                reportHandleService.handle(
                    command(reportId, ReportStatus.RESOLVED, ReportResult.NO_ACTION, null)))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(ReportErrorCode.REPORT_ALREADY_HANDLED);
  }

  @DisplayName("되돌리는 전이는 REPORT_TRANSITION_NOT_ALLOWED 다.")
  @Test
  void handle_movesBackward() {
    long reportId = report(ReportStatus.PROCESSING);

    assertThatThrownBy(
            () -> reportHandleService.handle(command(reportId, ReportStatus.PENDING, null, null)))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(ReportErrorCode.REPORT_TRANSITION_NOT_ALLOWED);
  }

  @DisplayName("없는 신고는 REPORT_NOT_FOUND 404 다.")
  @Test
  void handle_isMissing() {
    assertThatThrownBy(
            () -> reportHandleService.handle(command(-1L, ReportStatus.PROCESSING, null, null)))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(ReportErrorCode.REPORT_NOT_FOUND);
  }

  /** 주입된 시계는 KST 다. 그대로 담으면 아홉 시간 앞선 값이 저장된다 (도메인 4장). */
  @DisplayName("처리 시각은 UTC 로 저장된다.")
  @Test
  void handleStoresUtc() {
    long reportId = report(ReportStatus.PENDING);

    reportHandleService.handle(command(reportId, ReportStatus.PROCESSING, null, null));

    assertThat(reload(reportId).getHandledAt())
        .isCloseTo(LocalDateTime.now(ZoneOffset.UTC), within(1, ChronoUnit.MINUTES));
  }

  private long report(ReportStatus status) {
    return aReport()
        .reporterId(REPORTER_ID)
        .target(ReportTargetType.USER, TARGET_SEQUENCE.incrementAndGet())
        .status(status)
        .insert(jdbc);
  }

  private Report reload(long reportId) {
    return reportRepository.findById(reportId).orElseThrow();
  }

  private static ReportHandleCommand command(
      long reportId, ReportStatus status, ReportResult result, String memo) {

    return new ReportHandleCommand(reportId, status, result, memo, ADMIN_ID);
  }
}
