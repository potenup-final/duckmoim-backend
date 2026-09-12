package com.duckmoim.chat.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.domain.Message;
import com.duckmoim.chat.domain.MessageListQuery;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatMessageRepository;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.common.exception.ErrorCode;
import com.duckmoim.safety.domain.Report;
import com.duckmoim.safety.domain.ReportReason;
import com.duckmoim.safety.domain.ReportTargetType;
import com.duckmoim.safety.exception.ReportErrorCode;
import com.duckmoim.safety.infra.ReportRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신고가 접수된 방만 관리자가 읽는가, 그리고 읽을 때마다 기록이 남는가 (AD-08).
 *
 * <p>이 티켓의 검증 기준 두 줄이 그것이다 — <b>「신고 없는 방 열람 시 403」</b> 과 <b>「열람 시 감사 로그 1건」</b>.
 *
 * <p>작성자는 V11 시드를 쓴다 — 2 신고자 · 4 방장.
 */
@SpringBootTest
@Transactional
class AdminChatReadServiceTest {

  private static final long ADMIN_ID = 3L;
  private static final long REPORTER_ID = 2L;
  private static final long HOST_ID = 4L;

  @Autowired private AdminChatReadService adminChatReadService;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private ChatMessageRepository chatMessageRepository;
  @Autowired private ReportRepository reportRepository;
  @Autowired private JdbcTemplate jdbc;

  private long roomId;
  private long messageId;

  @BeforeEach
  void setUp() {
    long postId = aCompanionPost().hostId(HOST_ID).insert(jdbc);
    roomId = chatRoomRepository.saveAndFlush(ChatRoom.openFor(postId, HOST_ID)).getId();
    messageId = send("문제의 말");
  }

  @DisplayName("방이 신고된 건이면 대화를 읽을 수 있다.")
  @Test
  void readMessages() {
    long reportId = report(ReportTargetType.ROOM, roomId);

    MessageSlice slice = adminChatReadService.readMessages(query(), ADMIN_ID, reportId);

    assertThat(slice.items()).extracting(MessageView::content).containsExactly("문제의 말");
  }

  /** 대화 한 줄을 신고했는데 앞뒤 맥락을 못 보면 판단할 수 없다. */
  @DisplayName("메시지가 신고된 건이면 그 방의 대화를 읽을 수 있다.")
  @Test
  void readMessages_reportTargetsMessage() {
    long reportId = report(ReportTargetType.MESSAGE, messageId);

    MessageSlice slice = adminChatReadService.readMessages(query(), ADMIN_ID, reportId);

    assertThat(slice.items()).hasSize(1);
  }

  /** 이 티켓의 검증 기준이다 — 「신고 없는 방 열람 시 403」. */
  @DisplayName("남의 방을 가리키는 신고로는 이 방을 열 수 없다.")
  @Test
  void readMessages_reportTargetsAnotherRoom() {
    long otherPostId = aCompanionPost().hostId(HOST_ID).insert(jdbc);
    long otherRoomId =
        chatRoomRepository.saveAndFlush(ChatRoom.openFor(otherPostId, HOST_ID)).getId();
    long reportId = report(ReportTargetType.ROOM, otherRoomId);

    assertThatThrownBy(() -> adminChatReadService.readMessages(query(), ADMIN_ID, reportId))
        .isInstanceOf(BusinessException.class)
        .extracting(AdminChatReadServiceTest::errorCodeOf)
        .isEqualTo(ChatErrorCode.CHAT_ROOM_NOT_REPORTED);
  }

  /**
   * <b>없는 신고는 404 다.</b> 403 으로 뭉치면 관문의 403 과 구분되지 않아 권한 표 검사가 등급이 아니라 본문을 보게 된다. 이 경로는 관리자 전용이고
   * 관리자는 신고 목록을 이미 보므로 숨겨서 얻는 것이 없다.
   */
  @DisplayName("없는 신고 번호로 열면 404 다.")
  @Test
  void readMessages_reportIsMissing() {
    assertThatThrownBy(() -> adminChatReadService.readMessages(query(), ADMIN_ID, 404_404L))
        .isInstanceOf(BusinessException.class)
        .extracting(AdminChatReadServiceTest::errorCodeOf)
        .isEqualTo(ReportErrorCode.REPORT_NOT_FOUND);
  }

  /** 유저 신고로 남의 방을 여는 길이 생기면 안 된다. 대상 종류가 방·메시지가 아니면 전부 막힌다. */
  @DisplayName("유저를 가리키는 신고로는 방을 열 수 없다.")
  @Test
  void readMessages_reportTargetsUser() {
    long reportId = report(ReportTargetType.USER, HOST_ID);

    assertThatThrownBy(() -> adminChatReadService.readMessages(query(), ADMIN_ID, reportId))
        .isInstanceOf(BusinessException.class)
        .extracting(AdminChatReadServiceTest::errorCodeOf)
        .isEqualTo(ChatErrorCode.CHAT_ROOM_NOT_REPORTED);
  }

  /** 검증 기준의 나머지 한 줄 — 「열람 시 감사 로그 1건」. */
  @DisplayName("열람 한 번에 감사 로그가 한 건 남는다.")
  @Test
  void readMessages_leavesOneAuditLog() {
    long reportId = report(ReportTargetType.ROOM, roomId);

    adminChatReadService.readMessages(query(), ADMIN_ID, reportId);

    assertThat(auditLogCount()).isEqualTo(1);
    assertThat(auditDetail()).isEqualTo("신고 %d 처리 중 채팅 대화 열람".formatted(reportId));
  }

  @DisplayName("막힌 열람은 감사 로그를 남기지 않는다.")
  @Test
  void readMessages_leavesNoLogWhenBlocked() {
    long reportId = report(ReportTargetType.USER, HOST_ID);

    assertThatThrownBy(() -> adminChatReadService.readMessages(query(), ADMIN_ID, reportId))
        .isInstanceOf(BusinessException.class);

    assertThat(auditLogCount()).isZero();
  }

  /** 멤버에게는 자리표시자로 나가지만 (CH-12) 가린 뒤에도 판단 근거는 남아야 한다. */
  @DisplayName("지운 메시지의 본문도 관리자에게는 보인다.")
  @Test
  void readMessages_showsDeletedContent() {
    jdbc.update("UPDATE chat_message SET status = 'DELETED' WHERE id = ?", messageId);
    long reportId = report(ReportTargetType.MESSAGE, messageId);

    MessageSlice slice = adminChatReadService.readMessages(query(), ADMIN_ID, reportId);

    assertThat(slice.items()).extracting(MessageView::content).containsExactly("문제의 말");
  }

  private MessageListQuery query() {
    return new MessageListQuery(roomId, null, 30);
  }

  private long send(String content) {
    return chatMessageRepository
        .saveAndFlush(Message.send(roomId, HOST_ID, UUID.randomUUID().toString(), content))
        .getId();
  }

  private long report(ReportTargetType targetType, long targetId) {
    Report report = Report.of(REPORTER_ID, targetType, targetId, ReportReason.ABUSE, "신고 상세");

    return reportRepository.save(report).getId();
  }

  private int auditLogCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM audit_log WHERE kind = 'CHAT_READ' AND target_id = ?",
        Integer.class,
        roomId);
  }

  private String auditDetail() {
    List<String> details =
        jdbc.queryForList(
            "SELECT detail FROM audit_log WHERE kind = 'CHAT_READ' AND target_id = ?",
            String.class,
            roomId);

    return details.get(0);
  }

  private static ErrorCode errorCodeOf(Throwable thrown) {
    return ((BusinessException) thrown).getErrorCode();
  }
}
