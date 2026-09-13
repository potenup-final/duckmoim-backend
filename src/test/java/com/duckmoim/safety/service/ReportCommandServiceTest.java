package com.duckmoim.safety.service;

import static com.duckmoim.companion.CommentFixture.aComment;
import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.domain.Message;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatMessageRepository;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.common.exception.ErrorCode;
import com.duckmoim.companion.domain.CommentReadContext;
import com.duckmoim.companion.domain.CommentReadTarget;
import com.duckmoim.companion.domain.CommentStatus;
import com.duckmoim.companion.domain.CommentVisibilityPolicy;
import com.duckmoim.companion.exception.CommentErrorCode;
import com.duckmoim.companion.exception.PostErrorCode;
import com.duckmoim.companion.infra.CommentRepository;
import com.duckmoim.companion.infra.CompanionPostRepository;
import com.duckmoim.identity.exception.UserErrorCode;
import com.duckmoim.safety.domain.ReportReason;
import com.duckmoim.safety.domain.ReportStatus;
import com.duckmoim.safety.domain.ReportTargetType;
import com.duckmoim.safety.infra.ReportRepository;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
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
  @Autowired private CommentRepository commentRepository;
  @Autowired private CompanionPostRepository companionPostRepository;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private ChatMessageRepository chatMessageRepository;
  @Autowired private JdbcTemplate jdbc;

  private long postId;
  private long commentId;
  private long roomId;
  private long messageId;

  @BeforeEach
  void setUp() {
    postId = aCompanionPost().insert(jdbc);
    commentId = aComment().postId(postId).authorId(TARGET_USER_ID).insert(jdbc);
    roomId = chatRoomRepository.saveAndFlush(ChatRoom.openFor(postId, TARGET_USER_ID)).getId();
    messageId =
        chatMessageRepository
            .saveAndFlush(
                Message.send(roomId, TARGET_USER_ID, UUID.randomUUID().toString(), "문제의 말", null))
            .getId();
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

  /**
   * CM-14 의 검증 기준 — <b>「비밀 댓글도 신고 가능」</b>. 접수는 본문 열람 권한과 무관하다.
   *
   * <p><b>열람할 수 없다는 것을 판정기로 확인하고 시작한다.</b> 그러지 않으면 픽스처가 밀려 신고자가 방장이 되는 날 이 테스트가 조용히 아무것도 검증하지 않게 된다
   * — 이름은 그대로 「본문을 볼 수 없는 사람도」 인 채로.
   */
  @DisplayName("본문을 볼 수 없는 사람도 비밀 댓글을 신고할 수 있다.")
  @Test
  void reportSecretComment() {
    long secretCommentId =
        aComment().postId(postId).authorId(TARGET_USER_ID).secret(true).insert(jdbc);
    assertThat(canRead(secretCommentId, REPORTER_ID)).isFalse();

    Long reportId =
        reportCommandService.report(
            command(ReportTargetType.COMMENT, secretCommentId, ReportReason.ABUSE));

    assertThat(reportRepository.existsById(reportId)).isTrue();
  }

  /**
   * 사유를 손으로 적지 않고 조합표에서 뽑는다.
   *
   * <p>표 자체가 맞는지는 {@code ReportReasonTest} 가 전 21조합으로 본다. 여기서 같은 목록을 다시 적으면 둘이 갈라지고, 갈라진 뒤에는 어느 쪽이
   * 정본인지 알 수 없다.
   */
  @DisplayName("댓글 사유 넷 모두로 접수된다.")
  @ParameterizedTest(name = "{0}")
  @MethodSource("commentReasons")
  void reportComment_hasEveryCommentReason(ReportReason reason) {
    long targetCommentId = aComment().postId(postId).authorId(TARGET_USER_ID).insert(jdbc);

    Long reportId =
        reportCommandService.report(command(ReportTargetType.COMMENT, targetCommentId, reason));

    assertThat(reportRepository.existsById(reportId)).isTrue();
  }

  static List<ReportReason> commentReasons() {
    return Arrays.stream(ReportReason.values())
        .filter(reason -> reason.supports(ReportTargetType.COMMENT))
        .toList();
  }

  @DisplayName("같은 댓글을 다시 신고하면 거절한다.")
  @Test
  void reportComment_isDuplicated() {
    reportCommandService.report(
        command(ReportTargetType.COMMENT, commentId, ReportReason.INAPPROPRIATE));

    assertThatThrownBy(
            () ->
                reportCommandService.report(
                    command(ReportTargetType.COMMENT, commentId, ReportReason.ABUSE)))
        .isInstanceOf(BusinessException.class)
        .extracting(ReportCommandServiceTest::errorCodeOf)
        .isEqualTo(ReportErrorCodeHolder.DUPLICATED);
  }

  /**
   * CM-14 의 「확정 후 반영」 항목을 못박는다 — <b>지운 댓글 신고도 접수한다</b> (STAR-60 에서 결정).
   *
   * <p>근거는 {@link ReportTargetReader} 에 적었다. 요약하면 막으면 지우고 도망가는 길이 생기고, 조치가 유저 제재로 가므로 댓글이 없어진 뒤에도
   * 접수가 생산적이다.
   *
   * <p><b>이 테스트가 결정을 지키는 유일한 장치다.</b> 판정기는 {@code existsById} 라 상태를 보지 않아서, 누가 「소프트 삭제는 404」 규칙에
   * 맞추려고 상태 조건을 넣어도 다른 테스트는 하나도 빨개지지 않는다.
   */
  @DisplayName("지운 댓글도 신고로 접수된다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = CommentStatus.class,
      names = {"DELETED", "BLINDED"})
  void reportComment_isInactive(CommentStatus status) {
    long inactiveCommentId =
        aComment().postId(postId).authorId(TARGET_USER_ID).status(status).insert(jdbc);

    Long reportId =
        reportCommandService.report(
            command(ReportTargetType.COMMENT, inactiveCommentId, ReportReason.ABUSE));

    assertThat(reportRepository.existsById(reportId)).isTrue();
  }

  @DisplayName("없는 대상은 대상별 404 로 거절한다.")
  @Test
  void report_targetIsMissing() {
    assertNotFound(ReportTargetType.USER, ReportReason.NO_SHOW, UserErrorCode.USER_NOT_FOUND);
    assertNotFound(ReportTargetType.POST, ReportReason.OFF_TOPIC, PostErrorCode.POST_NOT_FOUND);
    assertNotFound(
        ReportTargetType.COMMENT, ReportReason.FALSE_INFO, CommentErrorCode.COMMENT_NOT_FOUND);
    assertNotFound(ReportTargetType.ROOM, ReportReason.ABUSE, ChatErrorCode.CHAT_ROOM_NOT_FOUND);
    assertNotFound(
        ReportTargetType.MESSAGE, ReportReason.ABUSE, ChatErrorCode.CHAT_MESSAGE_NOT_FOUND);
  }

  @DisplayName("채팅방을 신고하면 접수된다.")
  @Test
  void reportRoom() {
    Long reportId =
        reportCommandService.report(command(ReportTargetType.ROOM, roomId, ReportReason.ABUSE));

    assertThat(reportRepository.existsById(reportId)).isTrue();
  }

  @DisplayName("메시지를 신고하면 접수된다.")
  @Test
  void reportMessage() {
    Long reportId =
        reportCommandService.report(
            command(ReportTargetType.MESSAGE, messageId, ReportReason.INAPPROPRIATE));

    assertThat(reportRepository.existsById(reportId)).isTrue();
  }

  /**
   * 명세서 3장 미결 1번이 여기서 닫힌다 (2026-09-12).
   *
   * <p><b>접수는 방 멤버인지 보지 않는다.</b> 막으면 「괴롭히고 나가기」 길이 생기고, 괴롭힘을 당해 나간 사람이 정확히 그 모양이다. 지운 댓글을 신고할 수 있게
   * 둔 {@code CM-14} 와 같은 판단이다 (API-설계.md 「2-6. 신고 (Safety)」).
   *
   * <p>{@code REPORTER_ID} 는 이 방에 초대된 적이 없다 — 방장은 {@code TARGET_USER_ID} 다.
   */
  @DisplayName("방 멤버가 아닌 사람도 그 방과 메시지를 신고할 수 있다.")
  @Test
  void report_reporterIsNotMember() {
    Long roomReport =
        reportCommandService.report(command(ReportTargetType.ROOM, roomId, ReportReason.ABUSE));
    Long messageReport =
        reportCommandService.report(
            command(ReportTargetType.MESSAGE, messageId, ReportReason.ABUSE));

    assertThat(reportRepository.existsById(roomReport)).isTrue();
    assertThat(reportRepository.existsById(messageReport)).isTrue();
  }

  /** 지운 댓글과 같다 — 소프트 삭제라 본문이 남아 관리자가 판단할 재료가 된다 (CM-14). */
  @DisplayName("지운 메시지도 신고로 접수된다.")
  @Test
  void reportMessage_isDeleted() {
    jdbc.update("UPDATE chat_message SET status = 'DELETED' WHERE id = ?", messageId);

    Long reportId =
        reportCommandService.report(
            command(ReportTargetType.MESSAGE, messageId, ReportReason.ABUSE));

    assertThat(reportRepository.existsById(reportId)).isTrue();
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

  /** 도메인 7.1 의 판정기를 그대로 쓴다. 신고자가 작성자도 방장도 부모 댓글 작성자도 아니면 비밀 댓글 본문을 볼 수 없다. */
  private boolean canRead(long targetCommentId, long requesterId) {
    Long hostId = companionPostRepository.findById(postId).orElseThrow().getHostId();
    CommentReadTarget target =
        CommentReadTarget.of(commentRepository.findById(targetCommentId).orElseThrow());

    return new CommentVisibilityPolicy()
        .canReadContent(target, new CommentReadContext(requesterId, hostId, null));
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
