package com.duckmoim.chat.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.infra.ChatRoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 같은 사람이 같은 식별자로 동시에 보내는 경합 (CH-07 · I-20).
 *
 * <p><b>사전 조회만으로는 뚫린다.</b> {@code ChatMessageSendService} 가 먼저 {@code
 * findBySenderIdAndClientMessageId} 로 확인하지만, 두 요청이 같은 순간에 그 조회를 지나면 둘 다 통과한다. 실제 차단은 {@code
 * uq_chat_message_sender_client_id} 가 하고, 그 위반을 잡아 <b>먼저 저장된 건을 돌려주는 것</b>이 I-20 의 「기존 건 반환」이다.
 *
 * <p><b>실패가 아니라 같은 답이어야 한다는 것이 초대와 다른 점이다.</b> {@code ChatRoomInviteConcurrencyTest} 는 「한 건만 성공,
 * 나머지는 409」를 본다. 여기는 <b>전부 성공하고 전부 같은 메시지 번호</b>여야 한다 — 재시도가 안전하다는 것이 이 기능의 계약이기 때문이다.
 *
 * <p><b>{@code @Transactional} 이 없다.</b> 테스트 컨벤션이 <i>"별도 스레드는 테스트의 트랜잭션에 참여하지 않아서, 롤백을 걸면 테스트가 항상
 * 통과하는 상태가 된다"</i> 고 못박았다. 그래서 넣은 데이터를 손으로 지운다.
 */
@SpringBootTest
@DisplayName("동시 전송")
class ChatMessageSendConcurrencyTest {

  private static final long HOST_ID = 7L;
  private static final long MEMBER_ID = 11L;

  private static final LocalDateTime MEET_AT_UTC = LocalDateTime.of(2026, 10, 1, 9, 0);

  /** 만남 다음 날. 쓸 수 있는 구간 한가운데라 CH-08 이 이 테스트에 끼어들지 않는다. */
  private static final Instant NOW = MEET_AT_UTC.plusDays(1).toInstant(ZoneOffset.UTC);

  @TestConfiguration
  static class FixedClockConfig {

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
    }
  }

  @Autowired private ChatMessageSendService chatMessageSendService;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private JdbcTemplate jdbc;

  private long postId;

  /** 롤백이 없으니 손으로 지운다. 남기면 다음 테스트가 방 하나(I-16)에 걸린다. */
  @AfterEach
  void tearDown() {
    if (postId != 0) {
      jdbc.update(
          "DELETE FROM chat_message WHERE room_id IN"
              + " (SELECT id FROM chat_room WHERE post_id = ?)",
          postId);
      jdbc.update(
          "DELETE FROM chat_room_member WHERE room_id IN"
              + " (SELECT id FROM chat_room WHERE post_id = ?)",
          postId);
      jdbc.update("DELETE FROM chat_room WHERE post_id = ?", postId);
      jdbc.update("DELETE FROM companion_post WHERE id = ?", postId);
    }
  }

  @DisplayName("같은 클라이언트 식별자로 동시에 보내도 한 건만 저장되고 모두 같은 메시지를 받는다.")
  @Test
  void send_isConcurrent() throws Exception {
    long roomId = openRoomWithMember();
    String clientMessageId = UUID.randomUUID().toString();

    int threads = 10;
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    Set<Long> messageIds = ConcurrentHashMap.newKeySet();
    AtomicInteger failed = new AtomicInteger();
    AtomicReference<Exception> failure = new AtomicReference<>();

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (int i = 0; i < threads; i++) {
        pool.submit(
            () -> {
              ready.countDown();
              try {
                start.await();
                messageIds.add(
                    chatMessageSendService
                        .send(roomId, MEMBER_ID, clientMessageId, "동시에 누른 말", null)
                        .messageId());
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } catch (Exception e) {
                failed.incrementAndGet();
                failure.set(e);
              }
            });
      }

      ready.await(5, TimeUnit.SECONDS);
      start.countDown();
    } finally {
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(failed.get()).describedAs("실패한 요청: %s", failure.get()).isZero();
    assertThat(messageIds).describedAs("모두 같은 메시지를 받아야 한다").hasSize(1);
    assertThat(countMessagesOf(roomId)).isEqualTo(1);
  }

  private long openRoomWithMember() {
    postId = aCompanionPost().hostId(HOST_ID).meetAt(MEET_AT_UTC).insert(jdbc);

    ChatRoom room = ChatRoom.openFor(postId, HOST_ID);
    room.invite(MEMBER_ID);

    return chatRoomRepository.saveAndFlush(room).getId();
  }

  private int countMessagesOf(long roomId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM chat_message WHERE room_id = ?", Integer.class, roomId);
  }
}
