package com.duckmoim.safety.service;

import static com.duckmoim.companion.CommentFixture.aComment;
import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.common.exception.ErrorCode;
import com.duckmoim.companion.exception.CommentErrorCode;
import com.duckmoim.companion.exception.PostErrorCode;
import com.duckmoim.identity.exception.UserErrorCode;
import com.duckmoim.safety.domain.ReportReason;
import com.duckmoim.safety.domain.ReportStatus;
import com.duckmoim.safety.domain.ReportTargetType;
import com.duckmoim.safety.infra.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 접수의 검증 기준 (SF-01 · SF-02 · SF-07).
 *
 * <p>대상×사유 조합은 {@code ReportReasonTest} 가 21개로 덮는다. 여기서는 <b>저장소를 봐야 아는 것</b>을 본다 — 대상이 실재하는지, 이미
 * 신고했는지.
 *
 * <p>동시성은 {@code ReportConcurrencyTest} 가 따로 본다. 그 테스트에는 {@code @Transactional} 을 쓸 수 없다.
 *
 * <p>작성자는 V11 시드를 쓴다 — 2 신고자 · 4 신고 대상 · <b>5 탈퇴자</b>.
 */
@SpringBootTest
@Transactional
class ReportCommandServiceTest {

  private static final long REPORTER_ID = 2L;
  private static final long OTHER_REPORTER_ID = 3L;
  private static final long TARGET_USER_ID = 4L;
  private static final long WITHDRAWN_USER_ID = 5L;

  @Autowired private ReportCommandService reportCommandService;
  @Autowired private ReportRepository reportRepository;
  @Autowired private JdbcTemplate jdbc;

  private long postId;
  private long commentId;

  @BeforeEach
  void setUp() {
    postId = aCompanionPost().insert(jdbc);
    commentId = aComment().postId(postId).authorId(TARGET_USER_ID).insert(jdbc);
  }

  @DisplayName("유저를 신고하면 PENDING 으로 접수된다.")
  @Test
  void reportUser() {
    Long reportId =
        reportCommandService.report(
            command(ReportTargetType.USER, TARGET_USER_ID, ReportReason.NO_SHOW));

    assertThat(reportRepository.findById(reportId))
        .get()
        .satisfies(
            report -> {
              assertThat(report.getStatus()).isEqualTo(ReportStatus.PENDING);
              assertThat(report.getReporterId()).isEqualTo(REPORTER_ID);
              assertThat(report.getTargetId()).isEqualTo(TARGET_USER_ID);
            });
  }

  @DisplayName("모집글을 신고하면 접수된다.")
  @Test
  void reportPost() {
    Long reportId =
        reportCommandService.report(command(ReportTargetType.POST, postId, ReportReason.OFF_TOPIC));

    assertThat(reportRepository.existsById(reportId)).isTrue();
  }

  @DisplayName("댓글을 신고하면 접수된다.")
  @Test
  void reportComment() {
    Long reportId =
        reportCommandService.report(
            command(ReportTargetType.COMMENT, commentId, ReportReason.FALSE_INFO));

    assertThat(reportRepository.existsById(reportId)).isTrue();
  }

  @DisplayName("없는 대상은 대상별 404 로 거절한다.")
  @Test
  void report_targetIsMissing() {
    assertNotFound(ReportTargetType.USER, ReportReason.NO_SHOW, UserErrorCode.USER_NOT_FOUND);
    assertNotFound(ReportTargetType.POST, ReportReason.OFF_TOPIC, PostErrorCode.POST_NOT_FOUND);
    assertNotFound(
        ReportTargetType.COMMENT, ReportReason.FALSE_INFO, CommentErrorCode.COMMENT_NOT_FOUND);
  }

  /** 정본이 USER_NOT_FOUND 의 근거를 「탈퇴 포함」 으로 적었다. */
  @DisplayName("탈퇴한 회원은 없는 것으로 본다.")
  @Test
  void report_targetUserHasWithdrawn() {
    assertThatThrownBy(
            () ->
                reportCommandService.report(
                    command(ReportTargetType.USER, WITHDRAWN_USER_ID, ReportReason.NO_SHOW)))
        .isInstanceOf(BusinessException.class)
        .extracting(ReportCommandServiceTest::errorCodeOf)
        .isEqualTo(UserErrorCode.USER_NOT_FOUND);
  }

  @DisplayName("같은 대상을 다시 신고하면 거절한다.")
  @Test
  void report_isDuplicated() {
    reportCommandService.report(command(ReportTargetType.USER, TARGET_USER_ID, ReportReason.ABUSE));

    assertThatThrownBy(
            () ->
                reportCommandService.report(
                    command(ReportTargetType.USER, TARGET_USER_ID, ReportReason.NO_SHOW)))
        .isInstanceOf(BusinessException.class)
        .extracting(ReportCommandServiceTest::errorCodeOf)
        .isEqualTo(ReportErrorCodeHolder.DUPLICATED);
  }

  /** 중복은 신고자별이다. 남이 같은 대상을 신고하는 것은 막지 않는다. */
  @DisplayName("다른 사람이 같은 대상을 신고하면 접수된다.")
  @Test
  void report_byAnotherReporter() {
    reportCommandService.report(command(ReportTargetType.USER, TARGET_USER_ID, ReportReason.ABUSE));

    Long reportId =
        reportCommandService.report(
            new ReportCommand(
                OTHER_REPORTER_ID,
                ReportTargetType.USER,
                TARGET_USER_ID,
                ReportReason.ABUSE,
                null));

    assertThat(reportRepository.existsById(reportId)).isTrue();
  }

  /** 대상이 다르면 같은 사람이 여러 번 신고할 수 있다. */
  @DisplayName("같은 사람이 다른 대상을 신고하면 접수된다.")
  @Test
  void report_anotherTarget() {
    reportCommandService.report(command(ReportTargetType.USER, TARGET_USER_ID, ReportReason.ABUSE));

    Long reportId =
        reportCommandService.report(command(ReportTargetType.POST, postId, ReportReason.ABUSE));

    assertThat(reportRepository.existsById(reportId)).isTrue();
  }

  /**
   * 유니크 제약이 세 컬럼인 것을 직접 찝는다.
   *
   * <p>서비스로는 만들 수 없는 상황이다 — 같은 번호가 유저와 모집글로 동시에 실재해야 하고 그것은 시드에 달렸다. 확인하려는 것이 <b>제약의 모양</b>이라 SQL 로
   * 두 행을 넣는다. (reporter_id, target_id) 두 컬럼짜리였다면 두 번째가 터진다.
   */
  @DisplayName("id 가 같아도 대상 종류가 다르면 중복이 아니다.")
  @Test
  void uniqueKeyIncludesTargetType() {
    insertReport(ReportTargetType.POST, 99L);

    insertReport(ReportTargetType.USER, 99L);

    assertThat(countReportsOf(99L)).isEqualTo(2);
  }

  @DisplayName("상세는 안 적어도 접수된다.")
  @Test
  void report_hasNoDetail() {
    Long reportId =
        reportCommandService.report(
            new ReportCommand(
                REPORTER_ID, ReportTargetType.USER, TARGET_USER_ID, ReportReason.ABUSE, null));

    assertThat(reportRepository.findById(reportId))
        .get()
        .satisfies(report -> assertThat(report.getDetail()).isNull());
  }

  private void assertNotFound(
      ReportTargetType targetType, ReportReason reason, ErrorCode expected) {

    assertThatThrownBy(() -> reportCommandService.report(command(targetType, -1L, reason)))
        .isInstanceOf(BusinessException.class)
        .extracting(ReportCommandServiceTest::errorCodeOf)
        .isEqualTo(expected);
  }

  private void insertReport(ReportTargetType targetType, long targetId) {
    jdbc.update(
        """
        INSERT INTO report (reporter_id, target_type, target_id, reason, detail, status,
                            created_at, updated_at)
        VALUES (?, ?, ?, 'ABUSE', NULL, 'PENDING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
        """,
        REPORTER_ID,
        targetType.name(),
        targetId);
  }

  private int countReportsOf(long targetId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM report WHERE reporter_id = ? AND target_id = ?",
        Integer.class,
        REPORTER_ID,
        targetId);
  }

  private static ReportCommand command(
      ReportTargetType targetType, long targetId, ReportReason reason) {

    return new ReportCommand(REPORTER_ID, targetType, targetId, reason, "상세를 적었습니다.");
  }

  private static ErrorCode errorCodeOf(Throwable thrown) {
    return ((BusinessException) thrown).getErrorCode();
  }

  /** import 이름이 겹쳐 읽기 어려워지는 것을 피한다. */
  private static final class ReportErrorCodeHolder {
    private static final ErrorCode DUPLICATED =
        com.duckmoim.safety.exception.ReportErrorCode.REPORT_DUPLICATED;
  }
}
