package com.duckmoim.chat.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatMessageRepository;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.domain.ClosedReason;
import com.duckmoim.companion.domain.PostStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 전송의 검증 기준 네 줄 (CH-07 · CH-08).
 *
 * <p>명세에서 그대로 옮겼다 — 「같은 클라이언트 식별자로 두 번 보내면 한 건」 · 「비멤버 전송 시 403」 · 「방장이 모집을 완료해도 전송 200」 · 「만남시각 +
 * 7일 경과 후 전송 시 409」.
 *
 * <p><b>시계를 대역으로 바꾼다.</b> 만남시각 + 7일이라는 판정이 실행 시각에 달려 있어, 고정하지 않으면 픽스처의 날짜가 지나는 날 테스트가 저절로 빨간불이 된다.
 * 시계만 바꾸고 나머지는 실물이다 — 유니크 제약이 이 티켓의 이중 방어라 DB 를 대역으로 둘 수 없다.
 *
 * <p><b>{@code @Transactional} 을 붙이지 않았다.</b> 「두 번 보내면 한 건」이 커밋된 행을 다시 읽어 확인하는 검사이고, {@code
 * ChatMessageSendService} 가 일부러 트랜잭션 밖에 서 있다 — 테스트가 하나로 묶으면 그 구조가 검사에서 사라진다. 대신 각 테스트가 자기가 넣은 행만
 * 본다.
 */
@SpringBootTest
@DisplayName("메시지 전송")
class ChatMessageSendServiceTest {

  private static final long HOST_ID = 7L;
  private static final long MEMBER_ID = 11L;
  private static final long STRANGER_ID = 12L;

  /** UTC 로 저장되는 값이다 (CompanionPost#toUtc). */
  private static final LocalDateTime MEET_AT_UTC = LocalDateTime.of(2026, 10, 1, 9, 0);

  /**
   * 테스트마다 다른 순간에 세울 수 있는 시계.
   *
   * <p>{@code Clock.fixed} 를 {@code @TestConfiguration} 으로 박는 것이 이 저장소의 패턴이지만 ({@code
   * MeetTimePassedCloseBatchTest}) 그것은 클래스 하나에 한 순간이다. 여기는 <b>구간 안·경계·구간 밖</b>이 각각의 검증 기준이라 순간이 여럿
   * 필요하고, 클래스를 셋으로 쪼개면 스프링 컨텍스트가 셋이 된다.
   *
   * <p>시간대를 {@code Asia/Seoul} 로 두는 것은 {@code ClockConfig} 와 같게 맞추려는 것이다 — <b>판정이 그 시계에서 UTC 를
   * 뽑아내는지가 검증 대상이다.</b>
   */
  private static final MovableClock CLOCK = new MovableClock();

  @TestConfiguration
  static class MovableClockConfig {

    @Bean
    @Primary
    Clock movableClock() {
      return CLOCK;
    }
  }

  @Autowired private ChatMessageSendService chatMessageSendService;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private ChatMessageRepository chatMessageRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @DisplayName("방 멤버가 보낸 메시지가 저장된다.")
  @Test
  void send() {
    standAt(MEET_AT_UTC.plusDays(1));
    long roomId = openRoomWithMember();

    SentMessage sent =
        chatMessageSendService.send(roomId, MEMBER_ID, newClientId(), "8시에 봬요", null);

    assertThat(sent.roomId()).isEqualTo(roomId);
    assertThat(sent.senderId()).isEqualTo(MEMBER_ID);
    assertThat(sent.content()).isEqualTo("8시에 봬요");
    assertThat(chatMessageRepository.findById(sent.messageId())).isPresent();
  }

  /**
   * `CH-07` 의 첫 검증 기준이자 `I-20` 이다.
   *
   * <p><b>같은 건이 돌아오는 것까지 본다.</b> 「한 건만 저장된다」만 확인하면 두 번째 요청이 예외로 끝나도 통과하는데, 그러면 재시도가 안전하다는 성질이 사라진다
   * — 전송 응답을 못 받은 클라이언트가 다시 보내는 것이 정상 경로다.
   */
  @DisplayName("같은 클라이언트 식별자로 두 번 보내면 한 건이고 같은 메시지가 돌아온다.")
  @Test
  void sendTwiceWithSameClientMessageId() {
    standAt(MEET_AT_UTC.plusDays(1));
    long roomId = openRoomWithMember();
    String clientMessageId = newClientId();

    SentMessage first =
        chatMessageSendService.send(roomId, MEMBER_ID, clientMessageId, "8시에 봬요", null);
    SentMessage second =
        chatMessageSendService.send(roomId, MEMBER_ID, clientMessageId, "8시에 봬요", null);

    assertThat(second.messageId()).isEqualTo(first.messageId());
    assertThat(countMessagesOf(roomId)).isEqualTo(1);
  }

  /** 식별자가 같아도 보낸 사람이 다르면 남남이다 — 유니크 제약의 범위가 {@code (sender_id, client_message_id)} 인 것의 뜻이다. */
  @DisplayName("다른 사람이 같은 클라이언트 식별자를 보내도 각자 저장된다.")
  @Test
  void sendWithSameClientMessageIdByDifferentSenders() {
    standAt(MEET_AT_UTC.plusDays(1));
    long roomId = openRoomWithMember();
    String clientMessageId = newClientId();

    SentMessage mine = chatMessageSendService.send(roomId, MEMBER_ID, clientMessageId, "내 말", null);
    SentMessage hosts = chatMessageSendService.send(roomId, HOST_ID, clientMessageId, "방장 말", null);

    assertThat(hosts.messageId()).isNotEqualTo(mine.messageId());
    assertThat(hosts.content()).isEqualTo("방장 말");
  }

  /**
   * PR #125 리뷰가 잡은 자리다.
   *
   * <p>대조가 없으면 <b>B방에 보낸 말이 200 과 함께 사라진다</b> — 응답에는 A방 메시지가 실리고 B방에는 아무것도 안 남으며 아무 신호도 없다.
   */
  @DisplayName("다른 방에 같은 클라이언트 식별자를 쓰면 409 다.")
  @Test
  void sendWithSameClientMessageIdToAnotherRoom() {
    standAt(MEET_AT_UTC.plusDays(1));
    long roomA = openRoomWithMember();
    long roomB = openRoomWithMember();
    String clientMessageId = newClientId();

    chatMessageSendService.send(roomA, MEMBER_ID, clientMessageId, "8시에 봬요", null);

    assertThatThrownBy(
            () -> chatMessageSendService.send(roomB, MEMBER_ID, clientMessageId, "저 못 가요", null))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_CLIENT_MESSAGE_ID_REUSED);

    assertThat(countMessagesOf(roomB)).isZero();
  }

  /** 방이 같아도 본문이 다르면 재시도가 아니다. 멱등 키 규칙의 「같은 키 + 다른 파라미터」가 방 하나에도 그대로 적용된다. */
  @DisplayName("같은 방에서 본문만 바꿔 같은 식별자를 쓰면 409 다.")
  @Test
  void sendWithSameClientMessageIdAndDifferentContent() {
    standAt(MEET_AT_UTC.plusDays(1));
    long roomId = openRoomWithMember();
    String clientMessageId = newClientId();

    chatMessageSendService.send(roomId, MEMBER_ID, clientMessageId, "8시에 봬요", null);

    assertThatThrownBy(
            () -> chatMessageSendService.send(roomId, MEMBER_ID, clientMessageId, "9시로 바꿔요", null))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_CLIENT_MESSAGE_ID_REUSED);

    assertThat(countMessagesOf(roomId)).isEqualTo(1);
  }

  @DisplayName("방 멤버가 아닌 사람의 전송은 403 이다.")
  @Test
  void sendByNonMember() {
    standAt(MEET_AT_UTC.plusDays(1));
    long roomId = openRoomWithMember();

    assertThatThrownBy(
            () -> chatMessageSendService.send(roomId, STRANGER_ID, newClientId(), "끼어들기", null))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_ACCESS_DENIED);
  }

  /**
   * `CH-08` 의 본론이다.
   *
   * <p>도메인 6장 전이표가 {@code CLOSED} 행에 「만남시각 + 7일까지 가능」을 적었다 — 방장이 「모집 완료」를 누르는 순간이 조율의 시작이라 그때 대화를
   * 막으면 기능이 무너진다. {@code PostStatus} 를 보는 구현이 들어오면 여기서 잡힌다.
   */
  @DisplayName("모집이 마감된 글의 방에도 만남 전이면 전송할 수 있다.")
  @Test
  void sendToClosedPostRoom() {
    standAt(MEET_AT_UTC.minusDays(1));
    long roomId = openRoomWithMember(PostStatus.CLOSED);

    SentMessage sent = chatMessageSendService.send(roomId, MEMBER_ID, newClientId(), "마감됐네요", null);

    assertThat(sent.messageId()).isNotNull();
  }

  @DisplayName("만남시각 + 7일이 지나면 전송은 409 다.")
  @Test
  void sendAfterWritableWindow() {
    standAt(MEET_AT_UTC.plusDays(7).plusSeconds(1));
    long roomId = openRoomWithMember();

    assertThatThrownBy(
            () -> chatMessageSendService.send(roomId, MEMBER_ID, newClientId(), "늦은 말", null))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_READ_ONLY);
  }

  /** 구간이 지난 뒤 판정이 멤버 검사보다 늦다 — 멤버가 아닌 사람에게 방의 사정을 먼저 알려줄 이유가 없다. */
  @DisplayName("구간이 지난 방이어도 비멤버에게는 403 이 먼저다.")
  @Test
  void sendByNonMemberAfterWindow() {
    standAt(MEET_AT_UTC.plusDays(8));
    long roomId = openRoomWithMember();

    assertThatThrownBy(
            () -> chatMessageSendService.send(roomId, STRANGER_ID, newClientId(), "늦은 끼어들기", null))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_ACCESS_DENIED);
  }

  @DisplayName("없는 방으로 보내면 404 다.")
  @Test
  void sendToMissingRoom() {
    standAt(MEET_AT_UTC.plusDays(1));

    assertThatThrownBy(
            () -> chatMessageSendService.send(404404L, MEMBER_ID, newClientId(), "허공", null))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_NOT_FOUND);
  }

  /** 시계를 그 순간에 세운다. 인자는 UTC 벽시계다 — {@code meet_at} 과 같은 기준이라 테스트 본문이 시간대를 다시 계산하지 않는다. */
  private void standAt(LocalDateTime nowInUtc) {
    CLOCK.standAt(nowInUtc);
  }

  /** 시각만 움직이는 {@link Clock}. {@code Clock.fixed} 와 달리 한 컨텍스트에서 여러 순간을 쓸 수 있다. */
  private static final class MovableClock extends Clock {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private volatile Instant instant = Instant.EPOCH;

    void standAt(LocalDateTime nowInUtc) {
      this.instant = nowInUtc.toInstant(ZoneOffset.UTC);
    }

    @Override
    public ZoneId getZone() {
      return KST;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  private long openRoomWithMember() {
    return openRoomWithMember(PostStatus.OPEN);
  }

  private long openRoomWithMember(PostStatus status) {
    long postId =
        aCompanionPost()
            .hostId(HOST_ID)
            .meetAt(MEET_AT_UTC)
            .status(status)
            .closedReason(status == PostStatus.CLOSED ? ClosedReason.MANUAL : null)
            .insert(jdbcTemplate);

    ChatRoom room = ChatRoom.openFor(postId, HOST_ID);
    room.invite(MEMBER_ID);

    return chatRoomRepository.saveAndFlush(room).getId();
  }

  private int countMessagesOf(long roomId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM chat_message WHERE room_id = ?", Integer.class, roomId);
  }

  private String newClientId() {
    return UUID.randomUUID().toString();
  }
}
