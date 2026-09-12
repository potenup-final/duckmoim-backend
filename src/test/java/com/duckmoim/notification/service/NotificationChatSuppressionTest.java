package com.duckmoim.notification.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.domain.MessageEvent;
import com.duckmoim.chat.infra.ChatPresence;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.chat.service.ChatMessageSendService;
import com.duckmoim.chat.service.ChatRoomLeaveService;
import com.duckmoim.chat.service.ChatStreamService;
import com.duckmoim.chat.service.ChatStreamSession;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 채팅 알림과 접속 중 억제 (NT-06 · NT-07).
 *
 * <p><b>검증 기준이 한 줄이다</b> — 「접속 중 멤버에게 알림이 생기지 않는다」.
 *
 * <p><b>실물 Redis 를 쓰고 접속 집합을 대역으로 바꾸지 않는다.</b> 이 기능이 푸는 문제가 「보고 있다는 사실이 인스턴스 하나의 메모리에만 있다」라서, 가짜로
 * 바꾸면 검사하는 것이 배선이 아니라 픽스처가 된다. {@code ChatStreamServiceTest} · {@code RedisChatFanoutTest} 가 같은 근거로
 * 같은 방식을 쓴다.
 *
 * <p><b>「다른 인스턴스에서 보고 있다」를 인스턴스 둘 없이 만든다.</b> 다른 인스턴스가 하는 일은 자기 연결을 집합에 적는 것 하나이고, 그것은 {@link
 * ChatPresence#enter} 한 줄이다. 여기서 직접 부르면 <b>이 JVM 의 연결 목록에는 없는데 집합에는 있는</b> 상태가 되는데, 그것이 정확히 green 에
 * 붙은 사람을 blue 가 보는 모습이다.
 *
 * <p><b>클래스에 {@code @Transactional} 을 붙이지 않는다.</b> 아웃박스 발행이 전송 트랜잭션 안이라 붙이면 그 경계가 사라지고, 무엇보다 커밋된 행을
 * 다시 읽어야 한다. 대신 아웃박스를 앞뒤로 비운다.
 */
@SpringBootTest
@Testcontainers
@DisplayName("채팅 알림과 접속 중 억제")
class NotificationChatSuppressionTest {

  private static final LocalDateTime MEET_AT_UTC = LocalDateTime.of(2026, 10, 1, 9, 0);

  /** 로컬 compose · 운영과 같은 판본이다. 다르면 여기서 통과한 것이 운영에서 통과한다는 보장이 없다. */
  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

  @DynamicPropertySource
  static void redisConnection(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @Autowired private ChatStreamService chatStreamService;
  @Autowired private ChatMessageSendService chatMessageSendService;
  @Autowired private ChatRoomLeaveService chatRoomLeaveService;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private ChatPresence chatPresence;
  @Autowired private JdbcTemplate jdbc;

  private long hostId;
  private long memberId;
  private long otherId;
  private long roomId;

  @BeforeEach
  void setUp() {
    hostId = aUser().nickname("방장" + suffix()).insert(jdbc);
    memberId = aUser().nickname("멤버" + suffix()).insert(jdbc);
    otherId = aUser().nickname("다른멤버" + suffix()).insert(jdbc);

    long postId = aCompanionPost().hostId(hostId).meetAt(MEET_AT_UTC).insert(jdbc);

    ChatRoom room = ChatRoom.openFor(postId, hostId);
    room.invite(memberId);
    room.invite(otherId);
    roomId = chatRoomRepository.saveAndFlush(room).getId();

    clearOutbox();
  }

  @AfterEach
  void tearDown() {
    clearOutbox();
  }

  @DisplayName("메시지를 보내면 방의 다른 멤버에게 알림이 쌓인다.")
  @Test
  void send_notifiesOtherMembers() {
    // when
    chatMessageSendService.send(roomId, hostId, newClientId(), "8시에 3번 출구에서 봬요");

    // then — 보낸 사람을 뺀 둘이다
    assertThat(recipients()).containsExactlyInAnyOrder(memberId, otherId);
  }

  /**
   * <b>이 검사가 NT-07 의 검증 기준이다.</b>
   *
   * <p>화면에 이미 말풍선이 떠 있는 사람에게 알림을 만들면 배지가 그것을 한 번 더 센다.
   */
  @DisplayName("그 방을 보고 있는 멤버에게는 알림이 쌓이지 않는다.")
  @Test
  void send_skipsViewer() {
    // given
    chatStreamService.open(roomId, memberId, new SilentSession());

    // when
    chatMessageSendService.send(roomId, hostId, newClientId(), "보고 있는 사람은 빠진다");

    // then
    assertThat(recipients()).containsExactly(otherId);
  }

  /**
   * <b>인스턴스가 둘일 때가 이 기능이 있는 이유다.</b>
   *
   * <p>연결 목록만 보면 이 JVM 에 없는 사람은 「안 보고 있다」가 되어, ALB 가 green 으로 보낸 사람에게 알림이 생긴다. 검증 기준이 인스턴스 수에 따라
   * 참이었다 거짓이었다 하면 기준이 아니다.
   */
  @DisplayName("다른 인스턴스에서 보고 있는 멤버에게도 알림이 쌓이지 않는다.")
  @Test
  void send_skipsViewerOnAnotherInstance() {
    // given — 이 JVM 의 연결 목록에는 없고 접속 집합에만 있다
    chatPresence.enter(roomId, memberId);
    assertThat(chatStreamService.connectionCount(roomId)).isZero();

    // when
    chatMessageSendService.send(roomId, hostId, newClientId(), "green 에 붙은 사람도 빠진다");

    // then
    assertThat(recipients()).containsExactly(otherId);
  }

  /** 「자기 행동으로 자기에게 알림을 만들지 않는다」는 publisher 가 쥔다 (STAR-118). 채팅은 수신자가 여럿이라 보낸 사람이 목록에 그냥 들어 있다. */
  @DisplayName("보낸 사람에게는 알림이 쌓이지 않는다.")
  @Test
  void send_skipsSender() {
    // when
    chatMessageSendService.send(roomId, hostId, newClientId(), "내 말은 내가 안다");

    // then
    assertThat(recipients()).doesNotContain(hostId);
  }

  /** 나간 사람의 행은 남지만 멤버는 아니다 (CH-18). 알림이 가면 그 사람은 열 수 없는 방의 배지를 보게 된다. */
  @DisplayName("나간 멤버에게는 알림이 쌓이지 않는다.")
  @Test
  void send_skipsLeftMember() {
    // given
    chatRoomLeaveService.leave(roomId, memberId);
    clearOutbox();

    // when
    chatMessageSendService.send(roomId, hostId, newClientId(), "나간 사람은 빠진다");

    // then
    assertThat(recipients()).containsExactly(otherId);
  }

  /**
   * NT-06 이 <b>채팅방 초대를 의도적으로 넣지 않았다</b> (CH-02). 초대받은 사람은 방 목록에서 확인하거나 첫 메시지 알림으로 안다.
   *
   * <p>{@code setUp} 이 초대 둘을 하고 아웃박스를 비우지 않았다면 여기서 드러난다.
   */
  @DisplayName("초대만 하고 메시지가 없으면 알림이 쌓이지 않는다.")
  @Test
  void invite_doesNotNotify() {
    // then — setUp 의 초대 둘 말고는 아무 일도 하지 않았다
    assertThat(outboxRows()).isEmpty();
  }

  /** 억제가 「지금」에만 걸린다. 한 번 본 사람이 영영 안 받으면 그것은 수신 설정이지 억제가 아니다 (NT-11 은 STAR-124 다). */
  @DisplayName("스트림을 닫은 멤버에게는 다시 알림이 쌓인다.")
  @Test
  void send_notifiesAfterStreamClosed() {
    // given
    Runnable release = chatStreamService.open(roomId, memberId, new SilentSession());
    release.run();

    // when
    chatMessageSendService.send(roomId, hostId, newClientId(), "닫았으면 다시 받는다");

    // then
    assertThat(recipients()).containsExactlyInAnyOrder(memberId, otherId);
  }

  /**
   * 재시도가 안전하다는 것이 전송의 계약이다 (I-20). 알림이 두 번 쌓이면 <b>같은 말 하나에 배지가 둘</b> 오른다.
   *
   * <p>빠른 길이 발행 경로를 아예 지나지 않아서 성립한다 — 그 길을 없애면 여기서 걸린다.
   */
  @DisplayName("같은 식별자로 다시 보내도 알림이 한 번만 쌓인다.")
  @Test
  void send_publishesOnceOnRetry() {
    // given
    String clientMessageId = newClientId();
    chatMessageSendService.send(roomId, hostId, clientMessageId, "응답을 못 받았다");

    // when
    chatMessageSendService.send(roomId, hostId, clientMessageId, "응답을 못 받았다");

    // then
    assertThat(recipients()).containsExactlyInAnyOrder(memberId, otherId);
  }

  @DisplayName("채팅 알림은 모집글·댓글 없이 방과 메시지를 가리킨다.")
  @Test
  void send_targetsRoomAndMessage() {
    // when
    long messageId =
        chatMessageSendService.send(roomId, hostId, newClientId(), "어디로 갈지").messageId();

    // then — V806 이 만든 칸이다
    assertThat(outboxRows())
        .isNotEmpty()
        .allSatisfy(
            row -> {
              assertThat(row).containsEntry("kind", "ROOM_MESSAGED");
              assertThat(row).containsEntry("room_id", roomId);
              assertThat(row).containsEntry("message_id", messageId);
              assertThat(row.get("post_id")).isNull();
              assertThat(row.get("comment_id")).isNull();
            });
  }

  private List<Long> recipients() {
    return jdbc.queryForList(
        "SELECT recipient_id FROM notification_outbox ORDER BY recipient_id", Long.class);
  }

  private List<Map<String, Object>> outboxRows() {
    return jdbc.queryForList("SELECT * FROM notification_outbox");
  }

  private void clearOutbox() {
    jdbc.update("DELETE FROM notification_outbox");
  }

  private static String newClientId() {
    return UUID.randomUUID().toString();
  }

  private static String suffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  /** 무엇이 오는지는 여기서 보지 않는다. 「붙어 있다」는 사실만 있으면 된다. */
  private static final class SilentSession implements ChatStreamSession {

    @Override
    public void send(MessageEvent event) {
      // 검사하지 않는다. 실시간 전달은 CH-10 의 몫이다.
    }

    @Override
    public void beat() {
      // 검사하지 않는다.
    }

    @Override
    public void close() {
      // 검사하지 않는다.
    }
  }
}
