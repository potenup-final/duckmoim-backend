package com.duckmoim.chat.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.domain.Message;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatMessageRepository;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 어디까지 읽었는지 적기의 검증 기준 (CH-13).
 *
 * <p><b>{@code @Transactional} 이 없다.</b> 마지막 테스트가 별도 스레드에서 방 행을 잠그고 그 사이에 갱신이 지나가는지를 보는데, 테스트에
 * 트랜잭션을 걸면 그 잠금이 같은 트랜잭션 안에 들어가 아무것도 증명하지 못한다. 그래서 넣은 데이터를 손으로 지운다.
 */
@SpringBootTest
class ChatRoomReadServiceTest {

  private static final long HOST_ID = 7L;
  private static final long MEMBER_ID = 11L;
  private static final long STRANGER_ID = 12L;

  @Autowired private ChatRoomReadService chatRoomReadService;
  @Autowired private ChatRoomListQueryService chatRoomListQueryService;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private ChatMessageRepository chatMessageRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbc;

  @AfterEach
  void tearDown() {
    jdbc.update(
        "DELETE FROM chat_message WHERE room_id IN"
            + " (SELECT id FROM chat_room WHERE post_id IN"
            + " (SELECT id FROM companion_post WHERE title LIKE 'CH-13 픽스처%'))");
    jdbc.update(
        "DELETE FROM chat_room_member WHERE room_id IN"
            + " (SELECT id FROM chat_room WHERE post_id IN"
            + " (SELECT id FROM companion_post WHERE title LIKE 'CH-13 픽스처%'))");
    jdbc.update(
        "DELETE FROM chat_room WHERE post_id IN"
            + " (SELECT id FROM companion_post WHERE title LIKE 'CH-13 픽스처%')");
    jdbc.update("DELETE FROM companion_post WHERE title LIKE 'CH-13 픽스처%'");
  }

  @DisplayName("읽은 지점을 적는다.")
  @Test
  void markRead() {
    // given
    long roomId = roomWithMember("CH-13 픽스처 적기");
    long messageId = send(roomId, HOST_ID);

    // when
    chatRoomReadService.markRead(roomId, MEMBER_ID, messageId);

    // then
    assertThat(lastReadOf(roomId, MEMBER_ID)).isEqualTo(messageId);
  }

  @DisplayName("읽은 지점은 뒤로 가지 않는다.")
  @Test
  void markRead_doesNotGoBackward() {
    // given — 여러 기기에서 늦게 도착한 요청이 배지를 되살리면 안 된다
    long roomId = roomWithMember("CH-13 픽스처 역행");
    long older = send(roomId, HOST_ID);
    long newer = send(roomId, HOST_ID);
    chatRoomReadService.markRead(roomId, MEMBER_ID, newer);

    // when
    chatRoomReadService.markRead(roomId, MEMBER_ID, older);

    // then — 예외가 아니라 아무 일도 없는 것이 맞다. 뒤늦은 요청은 오류가 아니다
    assertThat(lastReadOf(roomId, MEMBER_ID)).isEqualTo(newer);
  }

  @DisplayName("같은 지점을 다시 적어도 결과가 같다.")
  @Test
  void markRead_isIdempotent() {
    // given
    long roomId = roomWithMember("CH-13 픽스처 멱등");
    long messageId = send(roomId, HOST_ID);
    chatRoomReadService.markRead(roomId, MEMBER_ID, messageId);

    // when
    chatRoomReadService.markRead(roomId, MEMBER_ID, messageId);

    // then
    assertThat(lastReadOf(roomId, MEMBER_ID)).isEqualTo(messageId);
  }

  @DisplayName("멤버가 아니면 읽은 지점을 적을 수 없다.")
  @Test
  void markRead_notMember() {
    long roomId = roomWithMember("CH-13 픽스처 비멤버");

    assertThatThrownBy(() -> chatRoomReadService.markRead(roomId, STRANGER_ID, 100L))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_ACCESS_DENIED);
  }

  @DisplayName("나간 사람은 읽은 지점을 적을 수 없다.")
  @Test
  void markRead_leftMember() {
    // given — CH-18. 나간 사람에게는 방 자체가 닫혀 있다
    long roomId = roomWithMember("CH-13 픽스처 나간사람");
    jdbc.update(
        "UPDATE chat_room_member SET left_at = UTC_TIMESTAMP(6) WHERE room_id = ? AND user_id = ?",
        roomId,
        MEMBER_ID);

    // then
    assertThatThrownBy(() -> chatRoomReadService.markRead(roomId, MEMBER_ID, 100L))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_ACCESS_DENIED);
  }

  @DisplayName("없는 방에는 읽은 지점을 적을 수 없다.")
  @Test
  void markRead_roomNotFound() {
    assertThatThrownBy(() -> chatRoomReadService.markRead(404_404L, MEMBER_ID, 100L))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_NOT_FOUND);
  }

  @DisplayName("그 방에 없는 번호는 읽은 지점으로 적히지 않는다.")
  @Test
  void markRead_ignoresIdNotInRoom() {
    // given — 방에 한 건뿐인데 클라이언트가 messageId 대신 Date.now() 를 보냈다
    long roomId = roomWithMember("CH-13 픽스처 범위밖");
    send(roomId, HOST_ID);

    // when
    chatRoomReadService.markRead(roomId, MEMBER_ID, System.currentTimeMillis());

    // then — 적혔다면 그 뒤로는 무엇이 와도 그보다 작아 배지가 영영 0 이 된다
    assertThat(lastReadOf(roomId, MEMBER_ID)).isNull();
  }

  @DisplayName("범위 밖 번호를 보낸 뒤에도 올바른 번호는 그대로 적힌다.")
  @Test
  void markRead_recoversAfterOutOfRangeId() {
    // given — 앞으로만 미는 규칙에 갇히지 않았는지를 본다. 갇히면 복구할 API 가 없다
    long roomId = roomWithMember("CH-13 픽스처 복구");
    long messageId = send(roomId, HOST_ID);
    chatRoomReadService.markRead(roomId, MEMBER_ID, System.currentTimeMillis());

    // when
    chatRoomReadService.markRead(roomId, MEMBER_ID, messageId);

    // then
    assertThat(lastReadOf(roomId, MEMBER_ID)).isEqualTo(messageId);
  }

  @DisplayName("범위 밖 번호를 보낸 뒤에 온 메시지도 배지에 오른다.")
  @Test
  void markRead_keepsBadgeAlive() {
    // given — 리뷰가 실측한 표 그대로다. 범위 밖 값이 적혔다면 여기서 배지가 영영 0 이 된다
    long roomId = roomWithMember("CH-13 픽스처 배지");
    send(roomId, HOST_ID);
    chatRoomReadService.markRead(roomId, MEMBER_ID, System.currentTimeMillis());

    // when
    send(roomId, HOST_ID);
    send(roomId, HOST_ID);

    // then — 읽은 적이 없으므로 세 건 다 안 읽은 것이다
    assertThat(badgeOf(roomId)).isEqualTo(3);
  }

  @DisplayName("방 행이 잠겨 있어도 읽은 지점 갱신은 기다리지 않는다.")
  @Test
  void markRead_doesNotLockRoom() throws Exception {
    // given — 남이 방 행을 잠근 채로 트랜잭션을 붙들고 있다
    long roomId = roomWithMember("CH-13 픽스처 잠금");
    long messageId = send(roomId, HOST_ID);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(1);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      pool.submit(
          () ->
              transactionTemplate.execute(
                  status -> {
                    jdbc.queryForObject(
                        "SELECT id FROM chat_room WHERE id = ? FOR UPDATE", Long.class, roomId);
                    locked.countDown();
                    await(done);
                    return null;
                  }));
      assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

      // when — 방을 잠근 트랜잭션이 살아 있는 동안 갱신을 보낸다
      Future<?> update =
          pool.submit(() -> chatRoomReadService.markRead(roomId, MEMBER_ID, messageId));

      // then — 방 행을 잠갔다면 여기서 상대가 커밋할 때까지 멈춰 선다
      update.get(5, TimeUnit.SECONDS);
      assertThat(lastReadOf(roomId, MEMBER_ID)).isEqualTo(messageId);
    } finally {
      done.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private long roomWithMember(String title) {
    long postId = aCompanionPost().title(title).insert(jdbc);
    ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.openFor(postId, HOST_ID));
    room.invite(MEMBER_ID);

    return chatRoomRepository.saveAndFlush(room).getId();
  }

  private long send(long roomId, long senderId) {
    return chatMessageRepository
        .saveAndFlush(Message.send(roomId, senderId, UUID.randomUUID().toString(), "한 마디", null))
        .getId();
  }

  private long badgeOf(long roomId) {
    return chatRoomListQueryService.findRooms(MEMBER_ID).stream()
        .filter(view -> view.roomId().equals(roomId))
        .findFirst()
        .orElseThrow()
        .unreadCount();
  }

  private Long lastReadOf(long roomId, long userId) {
    return jdbc.queryForObject(
        "SELECT last_read_message_id FROM chat_room_member WHERE room_id = ? AND user_id = ?",
        Long.class,
        roomId,
        userId);
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
