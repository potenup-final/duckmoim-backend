package com.duckmoim.chat.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.domain.Message;
import com.duckmoim.chat.domain.MessageListQuery;
import com.duckmoim.chat.domain.MessageStatus;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatMessageRepository;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.common.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신고 처리 결과로 메시지를 가리는 자리 (AD-09).
 *
 * <p>검증 기준은 한 줄이다 — <b>「블라인드 후 본문 미노출」</b>. 그래서 상태만 보지 않고 <b>멤버 목록에서 본문이 실제로 빠지는지</b>까지 본다.
 */
@SpringBootTest
@Transactional
class AdminMessageBlindServiceTest {

  private static final long ADMIN_ID = 3L;
  private static final long HOST_ID = 4L;

  @Autowired private AdminMessageBlindService adminMessageBlindService;
  @Autowired private ChatMessageQueryService chatMessageQueryService;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private ChatMessageRepository chatMessageRepository;
  @Autowired private JdbcTemplate jdbc;

  /** {@link #statusOf} 와 {@link #deleteDirectly} 가 쓴다. 이유는 그쪽에 적혀 있다. */
  @PersistenceContext private EntityManager entityManager;

  private long roomId;
  private long messageId;

  @BeforeEach
  void setUp() {
    long postId = aCompanionPost().hostId(HOST_ID).insert(jdbc);
    roomId = chatRoomRepository.saveAndFlush(ChatRoom.openFor(postId, HOST_ID)).getId();
    messageId =
        chatMessageRepository
            .saveAndFlush(Message.send(roomId, HOST_ID, UUID.randomUUID().toString(), "가릴 말", null))
            .getId();
  }

  @DisplayName("메시지를 가리면 상태가 BLINDED 가 된다.")
  @Test
  void blind() {
    adminMessageBlindService.blind(messageId, ADMIN_ID);

    assertThat(statusOf(messageId)).isEqualTo(MessageStatus.BLINDED.name());
  }

  /** 이 티켓의 검증 기준이다 — 「블라인드 후 본문 미노출」. 상태만 보면 목록이 그대로 내보내도 초록불이 난다. */
  @DisplayName("가려진 메시지의 본문은 멤버 목록에서 빠진다.")
  @Test
  void blind_hidesContentFromMembers() {
    adminMessageBlindService.blind(messageId, ADMIN_ID);

    MessageSlice slice =
        chatMessageQueryService.findMessages(new MessageListQuery(roomId, null, 30), HOST_ID);

    assertThat(slice.items()).extracting(MessageView::content).containsOnlyNulls();
    assertThat(slice.items())
        .extracting(MessageView::status)
        .containsExactly(MessageStatus.BLINDED);
  }

  @DisplayName("가릴 때마다 감사 로그가 한 건 남는다.")
  @Test
  void blind_leavesAuditLog() {
    adminMessageBlindService.blind(messageId, ADMIN_ID);

    assertThat(auditLogCount()).isEqualTo(1);
  }

  /** 부르는 쪽이 관리자라 본문까지 읽을 수 있어 (AD-08) 「없다」로 답하면 사실과 다르다. */
  @DisplayName("이미 가린 메시지를 다시 가리면 409 다.")
  @Test
  void blind_isAlreadyBlinded() {
    adminMessageBlindService.blind(messageId, ADMIN_ID);

    assertThatThrownBy(() -> adminMessageBlindService.blind(messageId, ADMIN_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(AdminMessageBlindServiceTest::errorCodeOf)
        .isEqualTo(ChatErrorCode.CHAT_MESSAGE_NOT_ACTIVE);
  }

  /** DELETED 와 BLINDED 는 각각 종착이고 둘 사이 전이가 없다 (도메인-모델링.md 「6. 라이프사이클」). */
  @DisplayName("보낸 사람이 지운 메시지는 가릴 수 없다.")
  @Test
  void blind_isDeleted() {
    deleteDirectly();

    assertThatThrownBy(() -> adminMessageBlindService.blind(messageId, ADMIN_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(AdminMessageBlindServiceTest::errorCodeOf)
        .isEqualTo(ChatErrorCode.CHAT_MESSAGE_NOT_ACTIVE);
  }

  @DisplayName("없는 메시지를 가리면 404 다.")
  @Test
  void blind_isMissing() {
    assertThatThrownBy(() -> adminMessageBlindService.blind(404_404L, ADMIN_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(AdminMessageBlindServiceTest::errorCodeOf)
        .isEqualTo(ChatErrorCode.CHAT_MESSAGE_NOT_FOUND);
  }

  /**
   * <b>먼저 flush 한다.</b> 블라인드는 영속성 컨텍스트에 올라온 엔티티를 고치고, 그 변경은 커밋이나 JPQL 실행 전까지 DB 에 안 내려간다.
   * JdbcTemplate 은 같은 커넥션을 쓰면서도 그 대기 중인 변경을 못 봐서, flush 없이 읽으면 방금 가린 메시지가 {@code ACTIVE} 로 보인다.
   *
   * <p>SQL 로 직접 읽는 것은 <b>표에 실제로 저장된 값</b>을 보기 위해서다.
   */
  private String statusOf(long id) {
    entityManager.flush();

    return jdbc.queryForObject("SELECT status FROM chat_message WHERE id = ?", String.class, id);
  }

  /**
   * 보낸 사람이 지운 상태를 만든다.
   *
   * <p><b>고친 뒤 컨텍스트를 비운다.</b> {@code setUp} 이 저장하면서 이 메시지가 영속성 컨텍스트에 올라와 있어, 비우지 않으면 서비스의 {@code
   * findById} 가 <b>DB 가 아니라 캐시의 {@code ACTIVE} 를 돌려준다</b> — 그러면 막혀야 할 호출이 통과한다.
   */
  private void deleteDirectly() {
    jdbc.update("UPDATE chat_message SET status = 'DELETED' WHERE id = ?", messageId);
    entityManager.clear();
  }

  private int auditLogCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM audit_log WHERE kind = 'MESSAGE_BLIND' AND target_id = ?",
        Integer.class,
        messageId);
  }

  private static ErrorCode errorCodeOf(Throwable thrown) {
    return ((BusinessException) thrown).getErrorCode();
  }
}
