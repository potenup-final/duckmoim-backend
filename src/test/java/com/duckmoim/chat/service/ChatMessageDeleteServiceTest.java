package com.duckmoim.chat.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.domain.Message;
import com.duckmoim.chat.domain.MessageStatus;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatMessageRepository;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 삭제의 검증 기준 (CH-12) — <b>삭제 후 본문 미노출, 자리표시자 유지.</b>
 *
 * <p>「본문 미노출」과 「자리표시자 유지」는 조회 쪽에서 보고 ({@code ChatMessageQueryServiceTest}), 여기서는 <b>누가 지울 수
 * 있는가</b>를 본다 — 상세가 「작성자 본인만」이라 권한 조합이 이 티켓의 갈림길이다.
 */
@SpringBootTest
@Transactional
@DisplayName("메시지 삭제")
class ChatMessageDeleteServiceTest {

  private static final LocalDateTime MEET_AT_UTC = LocalDateTime.of(2026, 10, 1, 9, 0);

  @Autowired private ChatMessageDeleteService chatMessageDeleteService;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private ChatMessageRepository chatMessageRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private long hostId;
  private long memberId;
  private long strangerId;
  private long roomId;

  @BeforeEach
  void setUp() {
    hostId = aUser().nickname("방장" + suffix()).insert(jdbcTemplate);
    memberId = aUser().nickname("멤버" + suffix()).insert(jdbcTemplate);
    strangerId = aUser().nickname("남" + suffix()).insert(jdbcTemplate);

    long postId = aCompanionPost().hostId(hostId).meetAt(MEET_AT_UTC).insert(jdbcTemplate);

    ChatRoom room = ChatRoom.openFor(postId, hostId);
    room.invite(memberId);
    roomId = chatRoomRepository.saveAndFlush(room).getId();
  }

  @DisplayName("보낸 사람이 지우면 상태가 DELETED 가 된다.")
  @Test
  void delete() {
    long messageId = send(memberId, "지울 말");

    chatMessageDeleteService.delete(roomId, messageId, memberId);

    assertThat(chatMessageRepository.findById(messageId).orElseThrow().getStatus())
        .isEqualTo(MessageStatus.DELETED);
  }

  /** 본문이 남아야 신고(CH-21)와 관리자 열람(AD-08)이 판단 재료를 갖는다. 응답에서 빼는 것과 지우는 것은 다른 일이다. */
  @DisplayName("지워도 본문은 표에 남는다.")
  @Test
  void delete_keepsContentInStorage() {
    long messageId = send(memberId, "지울 말");

    chatMessageDeleteService.delete(roomId, messageId, memberId);

    assertThat(chatMessageRepository.findById(messageId).orElseThrow().getContent())
        .isEqualTo("지울 말");
  }

  /**
   * <b>댓글과 갈리는 자리다.</b>
   *
   * <p>{@code CM-10} 은 작성자와 방장 둘에게 삭제를 줬지만 {@code CH-12} 의 상세는 「작성자 본인만」이다. 부적절한 메시지는 방 안의 권력이 아니라
   * 신고(CH-21)와 블라인드(AD-09)로 간다.
   */
  @DisplayName("방장이어도 남의 메시지는 지울 수 없다.")
  @Test
  void delete_rejectsHost() {
    long messageId = send(memberId, "방장이 지우려는 말");

    assertThatThrownBy(() -> chatMessageDeleteService.delete(roomId, messageId, hostId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_MESSAGE_NOT_SENDER);
  }

  @DisplayName("방 멤버가 아니면 403 이고 메시지까지 가지 않는다.")
  @Test
  void delete_rejectsNonMember() {
    long messageId = send(memberId, "남이 지우려는 말");

    assertThatThrownBy(() -> chatMessageDeleteService.delete(roomId, messageId, strangerId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_ACCESS_DENIED);
  }

  /** API 컨벤션의 「소프트 삭제된 리소스는 404 로 취급한다」. 지운 사람에게 그 메시지는 이미 없는 것이다. */
  @DisplayName("이미 지운 메시지를 다시 지우면 404 다.")
  @Test
  void delete_rejectsAlreadyDeleted() {
    long messageId = send(memberId, "두 번 지울 말");
    chatMessageDeleteService.delete(roomId, messageId, memberId);

    assertThatThrownBy(() -> chatMessageDeleteService.delete(roomId, messageId, memberId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_MESSAGE_NOT_FOUND);
  }

  @DisplayName("없는 메시지를 지우면 404 다.")
  @Test
  void delete_rejectsMissingMessage() {
    assertThatThrownBy(() -> chatMessageDeleteService.delete(roomId, 404404L, memberId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_MESSAGE_NOT_FOUND);
  }

  /**
   * 경로가 방과 메시지 둘을 따로 받아서 생기는 구멍이다.
   *
   * <p>대조하지 않으면 <b>내가 멤버인 방의 번호를 붙여 남의 방 메시지를 지울 수 있다.</b> 지울 수 있는 것이 자기 메시지뿐이라 피해가 크지는 않지만, 방 멤버
   * 판정이 아무 일도 하지 않게 되는 것이 문제다.
   */
  @DisplayName("다른 방의 메시지 번호로는 지울 수 없다.")
  @Test
  void delete_rejectsMessageFromAnotherRoom() {
    long otherPostId = aCompanionPost().hostId(memberId).meetAt(MEET_AT_UTC).insert(jdbcTemplate);
    ChatRoom otherRoom = ChatRoom.openFor(otherPostId, memberId);
    long otherRoomId = chatRoomRepository.saveAndFlush(otherRoom).getId();
    long messageId = send(otherRoomId, memberId, "남의 방에 있는 내 말");

    assertThatThrownBy(() -> chatMessageDeleteService.delete(roomId, messageId, memberId))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_MESSAGE_NOT_FOUND);
  }

  /**
   * 채팅 가능 구간(CH-08)은 <b>쓰기</b>를 막는 것이다.
   *
   * <p>만남 후 7일이 지났다고 자기 말을 못 지우게 할 근거가 없고, 오히려 그때가 지우고 싶어지는 때다. 전송 쪽 판정을 여기에 복사하면 그 판단이 뒤집힌다.
   */
  @DisplayName("채팅 가능 구간이 지난 방에서도 자기 메시지를 지울 수 있다.")
  @Test
  void delete_ignoresWritableWindow() {
    long messageId = send(memberId, "오래된 말");
    jdbcTemplate.update(
        "UPDATE companion_post SET meet_at = ? WHERE id ="
            + " (SELECT post_id FROM chat_room WHERE id = ?)",
        LocalDateTime.of(2020, 1, 1, 0, 0),
        roomId);

    assertThatCode(() -> chatMessageDeleteService.delete(roomId, messageId, memberId))
        .doesNotThrowAnyException();
  }

  private long send(long senderId, String content) {
    return send(roomId, senderId, content);
  }

  private long send(long targetRoomId, long senderId, String content) {
    return chatMessageRepository
        .saveAndFlush(Message.send(targetRoomId, senderId, UUID.randomUUID().toString(), content))
        .getId();
  }

  private static String suffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
