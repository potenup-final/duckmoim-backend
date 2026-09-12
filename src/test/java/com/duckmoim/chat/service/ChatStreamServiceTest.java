package com.duckmoim.chat.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.domain.MessageEvent;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatRoomRepository;
import com.duckmoim.common.exception.BusinessException;
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
  @Autowired private ChatRoomRepository chatRoomRepository;
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
    chatStreamService.open(roomId, memberId, session);

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
    chatStreamService.open(roomId, memberId, session);

    chatMessageSendService.send(roomId, hostId, newClientId(), "안녕하세요");

    session.awaitFirst();
    assertThat(session.received().get(0).senderNickname()).startsWith("방장");
  }

  /** 채널이 방마다 갈렸는지 본다. 한 채널에 다 실어 보내면 남의 방 대화가 흘러온다 — I-18 이 깨진다. */
  @DisplayName("다른 방의 메시지는 도착하지 않는다.")
  @Test
  void open_isScopedToRoom() throws Exception {
    RecordingSession session = new RecordingSession();
    chatStreamService.open(roomId, memberId, session);

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
    assertThatThrownBy(() -> chatStreamService.open(roomId, strangerId, new RecordingSession()))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_ACCESS_DENIED);
  }

  @DisplayName("없는 방의 스트림은 404 다.")
  @Test
  void open_rejectsMissingRoom() {
    assertThatThrownBy(() -> chatStreamService.open(404404L, memberId, new RecordingSession()))
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
    chatStreamService.open(roomId, memberId, leaving);

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
    chatStreamService.open(roomId, hostId, staying);
    chatStreamService.open(roomId, memberId, leaving);

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
    chatStreamService.open(roomId, hostId, first);
    chatStreamService.open(roomId, memberId, second);

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
    Runnable release = chatStreamService.open(roomId, memberId, new RecordingSession());
    assertThat(chatStreamService.connectionCount(roomId)).isEqualTo(1);

    release.run();

    assertThat(chatStreamService.connectionCount(roomId)).isZero();
  }

  /** 하트비트가 없으면 대화 없는 방의 연결을 ALB 가 60초마다 끊는다. */
  @DisplayName("하트비트는 열려 있는 연결에 신호를 보낸다.")
  @Test
  void heartbeat_beatsOpenConnections() {
    RecordingSession session = new RecordingSession();
    chatStreamService.open(roomId, memberId, session);

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
    chatStreamService.open(roomId, hostId, stalled);
    chatStreamService.open(roomId, memberId, healthy);

    chatStreamService.heartbeat();

    // 정체가 3초인데 1.5초 안에 와야 한다 — 직렬이면 못 온다.
    Awaitility.await().atMost(1500, TimeUnit.MILLISECONDS).until(() -> healthy.beats() == 1);
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
