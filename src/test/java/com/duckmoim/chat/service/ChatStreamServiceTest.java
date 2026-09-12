package com.duckmoim.chat.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.domain.MessageEvent;
import com.duckmoim.chat.domain.MessageStatus;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatFanout;
import com.duckmoim.chat.infra.ChatFanoutCodec;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.domain.AuthorDisplay;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
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
 * 실시간 수신의 검증 기준 (CH-10) — <b>다른 인스턴스에 붙은 멤버의 메시지도 도착한다.</b>
 *
 * <p><b>인스턴스 둘을 세우지 않고도 그 기준을 증명할 수 있다.</b> 이 기능이 푸는 문제는 「발행한 쪽과 받는 쪽이 같은 JVM 이 아니다」인데, Redis
 * Pub/Sub 은 발행자와 구독자가 같은 프로세스인지 구분하지 않는다. <b>메시지가 Redis 를 한 바퀴 돌아서 오는 것</b>만 확인하면 인스턴스가 갈려도 같은 경로다
 * — {@code RedisChatFanoutTest}(STAR-110)가 같은 근거로 같은 방식을 쓴다.
 *
 * <p><b>실물 Redis 를 쓴다.</b> 팬아웃을 대역으로 두면 검사하는 것이 구독 배선이 아니라 픽스처가 된다.
 *
 * <p><b>{@code SseEmitter} 가 없다.</b> {@link ChatStreamSession} 이 그 타입을 가려서 톰캣 없이 스트림 로직을 본다 — 포트를 둔
 * 값이 여기서 나온다.
 */
@SpringBootTest
@Testcontainers
@DisplayName("실시간 수신")
class ChatStreamServiceTest {

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
  @Autowired private ChatMessageDeleteService chatMessageDeleteService;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private ChatFanout chatFanout;
  @Autowired private ChatFanoutCodec chatFanoutCodec;
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

  /**
   * <b>이 검사가 CH-10 의 검증 기준이다.</b>
   *
   * <p>방장이 보낸 것이 <b>Redis 를 한 바퀴 돌아</b> 구독 중인 연결에 닿는지를 본다. 구독을 안 걸거나 발행을 빼면 여기서 걸린다.
   */
  @DisplayName("보낸 메시지가 그 방을 구독 중인 연결에 도착한다.")
  @Test
  void open_receivesPublishedMessage() {
    RecordingSession session = new RecordingSession();
    chatStreamService.open(roomId, memberId, session, null);

    chatMessageSendService.send(roomId, hostId, newClientId(), "8시에 3번 출구에서 봬요");

    session.awaitFirst();
    assertThat(session.received())
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.content()).isEqualTo("8시에 3번 출구에서 봬요");
              assertThat(event.senderId()).isEqualTo(hostId);
              assertThat(event.roomId()).isEqualTo(roomId);
            });
  }

  /** 말풍선에 이름이 뜨려면 보낸 사람 정보가 사건에 실려야 한다. 받는 쪽이 방 멤버 목록에서 찾게 하면 나간 사람이 이름 없이 뜬다. */
  @DisplayName("도착한 사건에 보낸 사람의 닉네임이 실려 있다.")
  @Test
  void open_carriesSenderDisplay() {
    RecordingSession session = new RecordingSession();
    chatStreamService.open(roomId, memberId, session, null);

    chatMessageSendService.send(roomId, hostId, newClientId(), "안녕하세요");

    session.awaitFirst();
    assertThat(session.received().get(0).senderNickname()).startsWith("방장");
  }

  /** 채널이 방마다 갈렸는지 본다. 한 채널에 다 실어 보내면 남의 방 대화가 흘러온다 — I-18 이 깨진다. */
  @DisplayName("다른 방의 메시지는 도착하지 않는다.")
  @Test
  void open_isScopedToRoom() throws Exception {
    RecordingSession session = new RecordingSession();
    chatStreamService.open(roomId, memberId, session, null);

    long otherPostId = aCompanionPost().hostId(memberId).meetAt(MEET_AT_UTC).insert(jdbcTemplate);
    long otherRoomId =
        chatRoomRepository.saveAndFlush(ChatRoom.openFor(otherPostId, memberId)).getId();
    chatMessageSendService.send(otherRoomId, memberId, newClientId(), "남의 방 말");

    TimeUnit.MILLISECONDS.sleep(300);
    assertThat(session.received()).isEmpty();
  }

  @DisplayName("방 멤버가 아니면 스트림을 열 수 없다.")
  @Test
  void open_rejectsNonMember() {
    assertThatThrownBy(
            () -> chatStreamService.open(roomId, strangerId, new RecordingSession(), null))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_ACCESS_DENIED);
  }

  @DisplayName("없는 방의 스트림은 404 다.")
  @Test
  void open_rejectsMissingRoom() {
    assertThatThrownBy(
            () -> chatStreamService.open(404404L, memberId, new RecordingSession(), null))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_NOT_FOUND);
  }

  /**
   * <b>퇴장이 연결을 끊는다</b> (CH-04 · CH-18 · A 안).
   *
   * <p>스트림은 붙는 순간 한 번만 멤버를 보고 몇 시간 열려 있다. 여기서 끊지 않으면 <b>나간 사람에게 대화가 계속 흘러간다</b> — 목록 조회는 매 요청 판정하므로
   * 같은 구멍이 없다.
   */
  @DisplayName("방을 나가면 그 사람의 연결이 끊기고 더 이상 받지 않는다.")
  @Test
  void leave_disconnectsStream() throws Exception {
    RecordingSession leaving = new RecordingSession();
    chatStreamService.open(roomId, memberId, leaving, null);

    chatRoomLeaveService.leave(roomId, memberId);

    assertThat(leaving.closed()).isTrue();
    assertThat(chatStreamService.connectionCount(roomId)).isZero();

    chatMessageSendService.send(roomId, hostId, newClientId(), "나간 뒤의 말");

    TimeUnit.MILLISECONDS.sleep(300);
    assertThat(leaving.received()).isEmpty();
  }

  /** 나간 사람만 끊어야 한다. 방의 구독 자체를 닫으면 남아 있는 사람도 실시간을 잃는다. */
  @DisplayName("한 사람이 나가도 남은 연결은 계속 받는다.")
  @Test
  void leave_keepsOtherConnections() {
    RecordingSession staying = new RecordingSession();
    RecordingSession leaving = new RecordingSession();
    chatStreamService.open(roomId, hostId, staying, null);
    chatStreamService.open(roomId, memberId, leaving, null);

    chatRoomLeaveService.leave(roomId, memberId);
    chatMessageSendService.send(roomId, hostId, newClientId(), "남은 사람에게만");

    staying.awaitFirst();
    assertThat(staying.received()).hasSize(1);
    assertThat(leaving.received()).isEmpty();
  }

  /** 구독은 방마다 하나다. 연결마다 걸면 같은 사건이 연결 수만큼 중복으로 온다. */
  @DisplayName("같은 방에 둘이 붙어도 각자 한 번씩만 받는다.")
  @Test
  void open_deliversOncePerConnection() {
    RecordingSession first = new RecordingSession();
    RecordingSession second = new RecordingSession();
    chatStreamService.open(roomId, hostId, first, null);
    chatStreamService.open(roomId, memberId, second, null);

    chatMessageSendService.send(roomId, hostId, newClientId(), "둘 다 받는다");

    first.awaitFirst();
    second.awaitFirst();
    assertThat(first.received()).hasSize(1);
    assertThat(second.received()).hasSize(1);
  }

  /** 정리 작업을 안 부르면 죽은 연결이 방마다 쌓인다. 컨트롤러가 세 콜백에 모두 거는 이유다. */
  @DisplayName("연결을 정리하면 목록에서 빠진다.")
  @Test
  void open_releaseRemovesConnection() {
    Runnable release = chatStreamService.open(roomId, memberId, new RecordingSession(), null);
    assertThat(chatStreamService.connectionCount(roomId)).isEqualTo(1);

    release.run();

    assertThat(chatStreamService.connectionCount(roomId)).isZero();
  }

  /** 하트비트가 없으면 대화 없는 방의 연결을 ALB 가 60초마다 끊는다. */
  @DisplayName("하트비트는 열려 있는 연결에 신호를 보낸다.")
  @Test
  void heartbeat_beatsOpenConnections() {
    RecordingSession session = new RecordingSession();
    chatStreamService.open(roomId, memberId, session, null);

    chatStreamService.heartbeat();

    Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> session.beats() == 1);
  }

  /**
   * <b>정체된 연결 하나가 나머지를 막지 않는다</b> (PR 리뷰).
   *
   * <p>모바일은 화면이 잠기거나 지하철에 들어가면 소켓이 죽지 않고 송신 버퍼만 찬다 — 그때 쓰기가 소켓 타임아웃까지 반환하지 않는다. 직렬로 돌면 그 한 명이 한 바퀴를
   * 수십 초로 늘려 <b>조용한 방의 멀쩡한 연결이 ALB 유휴 60초에 걸린다.</b>
   *
   * <p>여기서는 일부러 3초를 붙드는 세션을 끼워 두고, 그 뒤에 등록된 멀쩡한 연결이 <b>그 3초를 기다리지 않고</b> 신호를 받는지 본다. 직렬로 되돌리면 이 검사가
   * 시간 초과로 깨진다.
   */
  @DisplayName("정체된 연결이 있어도 다른 연결은 제때 신호를 받는다.")
  @Test
  void heartbeat_isNotBlockedByStalledConnection() {
    StallingSession stalled = new StallingSession();
    RecordingSession healthy = new RecordingSession();
    chatStreamService.open(roomId, hostId, stalled, null);
    chatStreamService.open(roomId, memberId, healthy, null);

    chatStreamService.heartbeat();

    // 정체가 3초인데 1.5초 안에 와야 한다 — 직렬이면 못 온다.
    Awaitility.await().atMost(1500, TimeUnit.MILLISECONDS).until(() -> healthy.beats() == 1);
  }

  // ── CH-11 재연결 시 누락 복구 ────────────────────────────────────────────────

  /**
   * <b>이 검사가 CH-11 의 검증 기준이다</b> — 끊고 그 사이 N건을 보낸 뒤 재연결하면 <b>유실 0건</b>.
   *
   * <p>끊긴 연결과 다시 붙은 연결을 한 검사 안에 둔 것은 <b>둘이 서로의 대조군</b>이기 때문이다. 앞의 것은 끊긴 뒤의 세 건을 못 받고 (그것이 D 까지의
   * 상태다) 뒤의 것은 다 받는다.
   */
  @DisplayName("끊긴 사이에 온 메시지를 재연결하면 하나도 빠짐없이 받는다.")
  @Test
  void reopen_replaysEveryMissedMessage() {
    RecordingSession beforeBreak = new RecordingSession();
    Runnable release = chatStreamService.open(roomId, memberId, beforeBreak, null);

    long lastReceived = send("받은 것");
    beforeBreak.awaitFirst();

    release.run(); // ✂ 배포 · 타임아웃 · 지하철

    List<Long> missed = List.of(send("그 사이 1"), send("그 사이 2"), send("그 사이 3"));

    RecordingSession reconnected = new RecordingSession();
    chatStreamService.open(roomId, memberId, reconnected, lastReceived);

    assertThat(beforeBreak.received()).hasSize(1); // 끊긴 쪽은 세 건을 못 봤다
    assertThat(reconnected.received())
        .extracting(MessageEvent::messageId)
        .containsSubsequence(missed.get(0), missed.get(1), missed.get(2));
  }

  /**
   * <b>재전송은 오래된 것부터다.</b> 순서가 뒤집히면 중간에 끊겼을 때 남는 구간이 이어지지 않는다 — 다음 재연결이 <b>이미 받은 뒤쪽</b>을 기준으로 삼게 된다.
   */
  @DisplayName("되돌려받은 메시지는 오래된 것부터 도착한다.")
  @Test
  void reopen_replaysOldestFirst() {
    long anchor = send("기준");
    send("그 사이 1");
    send("그 사이 2");

    RecordingSession reconnected = new RecordingSession();
    chatStreamService.open(roomId, memberId, reconnected, anchor);

    assertThat(reconnected.received()).extracting(MessageEvent::messageId).isSorted();
  }

  /**
   * <b>{@code id} 는 삽입 순서이지 커밋 순서가 아니다</b> ({@code MessageCursor} · PR #131 리뷰).
   *
   * <p>낮은 번호가 늦게 커밋되는 창이 있어 {@code id > lastId} 로 이어 읽으면 그 한 건이 영영 안 온다. 그래서 <b>재연결 지점보다 앞에서부터</b>
   * 읽고 클라이언트가 중복을 거른다. 여기서는 그 「앞」이 실제로 다시 오는지를 본다 — {@code BACKTRACK} 을 0 으로 되돌리면 깨진다.
   */
  @DisplayName("재연결 지점보다 앞의 메시지도 다시 보내 커밋 순서 구멍을 덮는다.")
  @Test
  void reopen_backtracksBeforeTheResumePoint() {
    long earlier = send("먼저 온 것");
    long resumeFrom = send("마지막으로 받은 것");

    RecordingSession reconnected = new RecordingSession();
    chatStreamService.open(roomId, memberId, reconnected, resumeFrom);

    assertThat(reconnected.received()).extracting(MessageEvent::messageId).contains(earlier);
  }

  /** 첫 연결에는 과거를 밀지 않는다. 화면은 목록 API(CH-09)가 채우고, 여기서도 밀면 같은 것이 두 경로로 온다. */
  @DisplayName("재연결 지점이 없으면 과거를 되돌려주지 않는다.")
  @Test
  void open_withoutResumePointReplaysNothing() {
    send("연결 전에 오간 말");

    RecordingSession fresh = new RecordingSession();
    chatStreamService.open(roomId, memberId, fresh, null);

    assertThat(fresh.received()).isEmpty();
  }

  /**
   * <b>너무 많이 밀리면 되돌려주지 않고 알린다.</b> 무한이면 며칠 끊겼던 클라이언트 하나가 수만 건을 끌어가 그 한 명의 재연결이 인스턴스의 메모리와 선로를 먹는다.
   *
   * <p>알림에 재개 지점을 그대로 실어 보내는지도 함께 본다 — 클라이언트가 목록을 어디까지 거슬러 올라가야 하는지가 그 값이다.
   */
  @DisplayName("되돌려줄 것이 상한을 넘으면 재전송 대신 따라잡으라고 알린다.")
  @Test
  void reopen_signalsGapWhenTooFarBehind() {
    long resumeFrom = send("기준");
    insertMessages(ChatStreamReplayReader.LIMIT + 1);

    RecordingSession reconnected = new RecordingSession();
    chatStreamService.open(roomId, memberId, reconnected, resumeFrom);

    assertThat(reconnected.received()).isEmpty();
    assertThat(reconnected.gaps()).containsExactly(resumeFrom);
  }

  /** 끊겨 있는 동안 지워진 메시지도 자리표시자로 와야 그 자리가 목록(CH-09 · CH-12)과 맞는다. */
  @DisplayName("끊긴 사이에 지워진 메시지는 본문 없이 자리표시자로 온다.")
  @Test
  void reopen_replaysDeletedMessageAsPlaceholder() {
    long resumeFrom = send("기준");
    long deleted = send("지울 말");
    chatMessageDeleteService.delete(roomId, deleted, hostId);

    RecordingSession reconnected = new RecordingSession();
    chatStreamService.open(roomId, memberId, reconnected, resumeFrom);

    assertThat(reconnected.received())
        .filteredOn(event -> event.messageId().equals(deleted))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.content()).isNull();
              assertThat(event.status()).isEqualTo(MessageStatus.DELETED);
            });
  }

  /** <b>재전송이 끝나면 실시간이 이어져야 한다.</b> 구독을 먼저 걸고 읽는 순서라 이 검사가 그 순서를 지킨다 — 뒤집으면 읽는 동안 발행된 것이 사라진다. */
  @DisplayName("되돌려준 뒤에도 새 메시지가 실시간으로 계속 온다.")
  @Test
  void reopen_keepsReceivingAfterReplay() {
    long resumeFrom = send("기준");
    send("그 사이");

    RecordingSession reconnected = new RecordingSession();
    chatStreamService.open(roomId, memberId, reconnected, resumeFrom);
    int replayed = reconnected.received().size();

    send("재연결한 뒤에 온 말");

    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> reconnected.received().size() > replayed);
  }

  /**
   * <b>재전송이 탈퇴자를 처음 만나는 경로다</b> (AU-11).
   *
   * <p>팬아웃은 방금 보낸 사람의 메시지라 탈퇴자를 만날 수 없지만, 재전송은 몇 시간 전 것을 읽어 그 사이 탈퇴한 사람을 만난다. 사건을 만드는 자리를 목록과 합치지
   * 않으면 <b>실시간 경로로만 실명이 남는다.</b>
   */
  @DisplayName("탈퇴한 사람의 옛 메시지는 자리표시자 이름으로 되돌아온다.")
  @Test
  void reopen_anonymizesWithdrawnSender() {
    long resumeFrom = send("기준");
    long fromWithdrawn = send("탈퇴 전에 남긴 말");
    jdbcTemplate.update("UPDATE user SET status = 'WITHDRAWN' WHERE id = ?", hostId);

    RecordingSession reconnected = new RecordingSession();
    chatStreamService.open(roomId, memberId, reconnected, resumeFrom);

    assertThat(reconnected.received())
        .filteredOn(event -> event.messageId().equals(fromWithdrawn))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.senderNickname()).isEqualTo(AuthorDisplay.WITHDRAWN_NICKNAME);
              assertThat(event.senderProfileImageUrl()).isNull();
            });
  }

  /** 방장이 보낸다. 돌려주는 값은 메시지 번호다. */
  private long send(String content) {
    return chatMessageSendService.send(roomId, hostId, newClientId(), content).messageId();
  }

  /**
   * 전송 경로를 거치지 않고 행만 밀어 넣는다.
   *
   * <p>상한 초과를 만들려면 백 건이 넘어야 하는데, 그 수를 전송 서비스로 만들면 검사 하나가 백 번의 트랜잭션이 된다. <b>여기서 보는 것은 개수이지 전송 규칙이
   * 아니다.</b>
   */
  private void insertMessages(int count) {
    jdbcTemplate.batchUpdate(
        """
        INSERT INTO chat_message (room_id, sender_id, client_message_id, content, status,
                                  created_at, updated_at)
        VALUES (?, ?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
        """,
        java.util.stream.IntStream.range(0, count)
            .mapToObj(i -> new Object[] {roomId, hostId, newClientId(), "밀린 말 " + i})
            .toList());
  }

  // ── 다중 인스턴스 퇴장 전파 (PR #138 리뷰) ────────────────────────────────────

  /**
   * <b>다른 인스턴스에서 나간 사람의 연결을 여기서 끊는다</b> (PR #138 리뷰).
   *
   * <p><b>인스턴스 둘을 세우지 않고도 그 기준을 증명할 수 있다.</b> CH-10 의 팬아웃 검사와 같은 근거다 — Redis Pub/Sub 은 발행자와 구독자가 같은
   * 프로세스인지 구분하지 않으므로, <b>퇴장 사건이 Redis 를 한 바퀴 돌아서 연결을 끊는 것</b>만 확인하면 인스턴스가 갈려도 같은 경로다.
   *
   * <p>여기서는 {@code ChatRoomLeaveService} 를 부르지 않고 <b>통로에 직접 발행한다.</b> 그것이 「다른 인스턴스가 퇴장을 처리했다」와 같은
   * 상황이고, 이 인스턴스의 로컬 맵에는 아무 일도 일어나지 않은 상태다.
   *
   * <p><b>고치기 전에는 이 검사가 실패한다.</b> 예전 {@code dispatch} 는 판독한 것을 무조건 말풍선으로 다뤄서, 퇴장 사건이 오면 그냥 버려졌다.
   */
  @DisplayName("다른 인스턴스에서 나간 사람의 연결이 이 인스턴스에서 끊긴다.")
  @Test
  void dispatch_disconnectsMemberWhoLeftOnAnotherInstance() {
    RecordingSession leaving = new RecordingSession();
    RecordingSession staying = new RecordingSession();
    chatStreamService.open(roomId, memberId, leaving, null);
    chatStreamService.open(roomId, hostId, staying, null);

    // 다른 인스턴스가 퇴장을 처리하고 통로에 알린 것과 같다.
    chatFanout.publish(roomId, chatFanoutCodec.encodeMemberLeft(memberId));

    Awaitility.await().atMost(5, TimeUnit.SECONDS).until(leaving::closed);
    assertThat(chatStreamService.connectionCount(roomId)).isEqualTo(1);

    chatMessageSendService.send(roomId, hostId, newClientId(), "나간 뒤의 말");

    staying.awaitFirst();
    assertThat(leaving.received()).isEmpty();
  }

  /** 남은 사람까지 끊으면 퇴장 한 번이 그 방의 실시간을 통째로 죽인다. */
  @DisplayName("전파된 퇴장은 나간 사람의 연결만 끊는다.")
  @Test
  void dispatch_leavesOtherConnectionsAlone() {
    RecordingSession leaving = new RecordingSession();
    RecordingSession staying = new RecordingSession();
    chatStreamService.open(roomId, memberId, leaving, null);
    chatStreamService.open(roomId, hostId, staying, null);

    chatFanout.publish(roomId, chatFanoutCodec.encodeMemberLeft(memberId));

    Awaitility.await().atMost(5, TimeUnit.SECONDS).until(leaving::closed);
    assertThat(staying.closed()).isFalse();
  }

  /**
   * 퇴장 사건이 말풍선으로 새면 화면에 빈 말풍선이 뜬다.
   *
   * <p>봉투에 종류를 적고 판독한 쪽이 갈라야 하는 이유가 이것이다 ({@code ChatFanoutEvent}).
   */
  @DisplayName("퇴장 사건은 말풍선으로 밀리지 않는다.")
  @Test
  void dispatch_doesNotPushLeaveAsMessage() {
    RecordingSession staying = new RecordingSession();
    chatStreamService.open(roomId, hostId, staying, null);

    chatFanout.publish(roomId, chatFanoutCodec.encodeMemberLeft(memberId));
    chatMessageSendService.send(roomId, hostId, newClientId(), "진짜 말풍선");

    staying.awaitFirst();
    assertThat(staying.received()).hasSize(1);
    assertThat(staying.received().get(0).content()).isEqualTo("진짜 말풍선");
  }

  private String newClientId() {
    return UUID.randomUUID().toString();
  }

  private static String suffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  /** 신호를 보내면 오래 붙드는 대역. 버퍼가 찬 모바일 연결을 흉내 낸다. */
  private static final class StallingSession implements ChatStreamSession {

    @Override
    public void send(MessageEvent event) {
      // 이 검사는 하트비트만 본다.
    }

    @Override
    public void sendGap(Long fromMessageId) {
      // 이 검사는 하트비트만 본다.
    }

    @Override
    public void beat() {
      try {
        TimeUnit.SECONDS.sleep(3);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void close() {
      // 정리할 것이 없다.
    }
  }

  /**
   * 받은 것을 기록하는 대역 연결.
   *
   * <p>Redis 구독 스레드가 채우고 테스트 스레드가 읽으므로 동시 접근에 안전한 목록을 쓴다.
   */
  private static final class RecordingSession implements ChatStreamSession {

    private final List<MessageEvent> received = new CopyOnWriteArrayList<>();
    private final List<Long> gaps = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger beatCount =
        new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean closed;

    @Override
    public void send(MessageEvent event) {
      received.add(event);
    }

    @Override
    public void sendGap(Long fromMessageId) {
      gaps.add(fromMessageId);
    }

    @Override
    public void beat() {
      beatCount.incrementAndGet();
    }

    @Override
    public void close() {
      closed = true;
    }

    /** 팬아웃이 비동기라 도착을 기다린다. 고정 대기를 쓰면 느린 기계에서 깨지고 빠른 기계에서 느려진다. */
    void awaitFirst() {
      Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> !received.isEmpty());
    }

    List<MessageEvent> received() {
      return received;
    }

    List<Long> gaps() {
      return gaps;
    }

    int beats() {
      return beatCount.get();
    }

    boolean closed() {
      return closed;
    }
  }
}
