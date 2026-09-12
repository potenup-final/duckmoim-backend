package com.duckmoim.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

import com.duckmoim.chat.domain.MessageEvent;
import com.duckmoim.chat.domain.MessageStatus;
import com.duckmoim.chat.infra.ChatFanout;
import com.duckmoim.chat.infra.ChatFanoutCodec;
import com.duckmoim.chat.infra.ChatFanoutEvent;
import com.duckmoim.chat.infra.ChatFanoutSubscription;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

/**
 * <b>등록한 뒤에 던지면 좀비가 남는다</b> (PR #142 리뷰).
 *
 * <p>컨트롤러는 {@code open()} 이 돌려준 손잡이로 {@code onCompletion} · {@code onTimeout} · {@code onError} 를
 * 거는데, 등록 뒤에 예외가 나가면 그 셋 중 아무것도 안 걸린다 — 연결이 목록에 남고 뺄 길이 없어진다. <b>피해는 좀비 하나가 아니라 그 방의 Redis 구독이
 * 재기동까지 안 닫히는 것</b>이다 ({@code unsubscribeIfEmpty} 가 「연결이 없을 때만」 닫기 때문이다).
 *
 * <p><b>스프링을 띄우지 않는다.</b> 보는 것이 「예외가 났을 때 맵이 어떻게 남는가」라 컨텍스트가 필요하지 않고, {@code @SpringBootTest} 를 하나
 * 더 만들면 남의 테스트가 커넥션을 못 얻는다 (CLAUDE.md 「겪은 함정」).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("스트림 열기 실패")
class ChatStreamOpenFailureTest {

  private static final long ROOM_ID = 3L;
  private static final long USER_ID = 11L;
  private static final long LAST_EVENT_ID = 101L;
  private static final String LIVE_PAYLOAD = "{}";

  @Mock private ChatRoomMembershipReader chatRoomReader;
  @Mock private ChatFanout chatFanout;
  @Mock private ChatFanoutCodec chatFanoutCodec;
  @Mock private ChatStreamHeartbeatExecutor heartbeatExecutor;
  @Mock private ChatStreamReplayReader replayReader;
  @Mock private ChatFanoutSubscription subscription;

  /** 구독 스레드가 받는 핸들러. 이것을 직접 부르면 실제 팬아웃 경로가 그대로 돈다. */
  @Captor private ArgumentCaptor<Consumer<String>> handlerCaptor;

  @InjectMocks private ChatStreamService chatStreamService;

  private final RecordingSession session = new RecordingSession();

  /** 고치기 전에는 {@code QueryTimeoutException} 이 그대로 컨트롤러까지 올라갔다. */
  @DisplayName("재전송 읽기가 터져도 스트림은 열린다.")
  @Test
  void open_survivesReplayFailure() {
    givenSubscribable();
    willThrow(new QueryTimeoutException("커넥션 고갈"))
        .given(replayReader)
        .readSince(anyLong(), anyLong());

    assertThatCode(() -> open(LAST_EVENT_ID)).doesNotThrowAnyException();
    assertThat(chatStreamService.connectionCount(ROOM_ID)).isEqualTo(1);
  }

  /** 상한 초과와 같은 대응이라 클라이언트에 새 규칙이 없다. 조용히 넘기면 못 받은 구간이 말없이 사라진다. */
  @DisplayName("재전송 읽기가 터지면 따라잡으라고 알린다.")
  @Test
  void open_signalsGapWhenReplayFails() {
    givenSubscribable();
    willThrow(new QueryTimeoutException("커넥션 고갈"))
        .given(replayReader)
        .readSince(anyLong(), anyLong());

    open(LAST_EVENT_ID);

    assertThat(session.gaps()).containsExactly(LAST_EVENT_ID);
  }

  /**
   * <b>구독이 터지면 연결을 남기지 않는다.</b>
   *
   * <p>재전송과 달리 구독 실패는 삼키지 않는다 — 구독 없는 스트림은 아무것도 받지 못해서, 열어 두는 것이 500 으로 끝내는 것보다 나쁘다 (브라우저의 {@code
   * EventSource} 는 오류에 스스로 다시 붙는다). 대신 <b>던지기 전에 직접 정리한다.</b>
   */
  @DisplayName("구독이 터지면 던지되 연결을 목록에 남기지 않는다.")
  @Test
  void open_removesConnectionWhenSubscribeFails() {
    willThrow(new IllegalStateException("Redis 불가")).given(chatFanout).subscribe(anyLong(), any());

    assertThatThrownBy(() -> open(null)).isInstanceOf(IllegalStateException.class);
    assertThat(chatStreamService.connectionCount(ROOM_ID)).isZero();
  }

  /** 좀비가 남으면 다음 열기가 이미 있는 구독을 재사용한 것으로 착각한다 — 그 방은 영영 구독 없이 돈다. */
  @DisplayName("구독이 터진 뒤 다시 열면 구독을 새로 건다.")
  @Test
  void open_resubscribesAfterSubscribeFailure() {
    willThrow(new IllegalStateException("Redis 불가"))
        .willReturn(subscription)
        .given(chatFanout)
        .subscribe(anyLong(), any());

    assertThatThrownBy(() -> open(null)).isInstanceOf(IllegalStateException.class);
    open(null);

    then(chatFanout).should(times(2)).subscribe(anyLong(), any());
    assertThat(chatStreamService.connectionCount(ROOM_ID)).isEqualTo(1);
  }

  /**
   * <b>재전송 중에 도착한 실시간이 먼저 실리면 안 된다</b> (PR #142 리뷰).
   *
   * <p>SSE 명세상 브라우저는 <b>마지막으로 받은</b> {@code id} 를 책갈피로 쓴다 — 가장 큰 값이 아니다. 재전송 도중 큰 번호가 끼어든 뒤 끊기면 다음
   * 재연결이 그 큰 번호를 기준으로 삼아 <b>사이 구간이 영영 안 온다.</b>
   *
   * <pre>
   * 선로   102, 103, [202], 104 …      ← 버퍼링 전
   *                  ▲ 여기서 끊기면 책갈피가 202 가 된다
   * 선로   102, 103, 104 … 201, 202    ← 버퍼링 후. 단조 증가
   * </pre>
   *
   * <p><b>구독 핸들러를 직접 부른다.</b> {@code chatFanout.subscribe} 가 받은 그 람다라, 구독 스레드가 하는 일과 같은 경로를 탄다 —
   * {@code ChatStreamService} 에 검사용 구멍을 내지 않는다.
   */
  @DisplayName("재전송 중에 온 실시간 메시지는 재전송이 끝난 뒤에 실린다.")
  @Test
  void open_buffersLiveEventsUntilReplayEnds() {
    givenSubscribable();
    given(chatFanoutCodec.decode(LIVE_PAYLOAD)).willReturn(ChatFanoutEvent.message(event(202L)));

    // 재전송이 102 · 103 을 만드는 사이에 구독 스레드가 202 를 밀어 넣는다.
    given(replayReader.readSince(anyLong(), anyLong()))
        .willAnswer(
            invocation -> {
              fanoutHandler().accept(LIVE_PAYLOAD);
              return MissedMessages.of(List.of(event(102L), event(103L)));
            });

    open(LAST_EVENT_ID);

    assertThat(session.sentIds()).containsExactly(102L, 103L, 202L);
  }

  /** 첫 연결에도 방출은 시작돼야 한다. 안 그러면 그 연결이 영원히 조용하다. */
  @DisplayName("재전송할 것이 없어도 실시간이 바로 흐른다.")
  @Test
  void open_startsDeliveringWithoutReplay() {
    givenSubscribable();
    given(chatFanoutCodec.decode(LIVE_PAYLOAD)).willReturn(ChatFanoutEvent.message(event(202L)));

    open(null);
    fanoutHandler().accept(LIVE_PAYLOAD);

    assertThat(session.sentIds()).containsExactly(202L);
  }

  /** 재전송이 터져도 버퍼가 열려야 한다 — {@code finally} 를 걷어내면 이 연결이 영원히 조용해진다. */
  @DisplayName("재전송이 터져도 그 뒤의 실시간은 흐른다.")
  @Test
  void open_startsDeliveringAfterReplayFailure() {
    givenSubscribable();
    given(chatFanoutCodec.decode(LIVE_PAYLOAD)).willReturn(ChatFanoutEvent.message(event(202L)));
    willThrow(new QueryTimeoutException("커넥션 고갈"))
        .given(replayReader)
        .readSince(anyLong(), anyLong());

    open(LAST_EVENT_ID);
    fanoutHandler().accept(LIVE_PAYLOAD);

    assertThat(session.sentIds()).containsExactly(202L);
  }

  private Consumer<String> fanoutHandler() {
    then(chatFanout).should().subscribe(anyLong(), handlerCaptor.capture());
    return handlerCaptor.getValue();
  }

  private static MessageEvent event(long messageId) {
    return new MessageEvent(
        messageId,
        ROOM_ID,
        USER_ID,
        "덕후1",
        null,
        "말",
        MessageStatus.ACTIVE,
        LocalDateTime.of(2026, 10, 2, 11, 10));
  }

  private void givenSubscribable() {
    given(chatFanout.subscribe(anyLong(), any())).willReturn(subscription);
  }

  private void open(Long lastEventId) {
    chatStreamService.open(ROOM_ID, USER_ID, session, lastEventId);
  }

  /** 밀린 것과 알림을 기록하는 대역. */
  private static final class RecordingSession implements ChatStreamSession {

    private final List<MessageEvent> received = new CopyOnWriteArrayList<>();
    private final List<Long> gaps = new CopyOnWriteArrayList<>();

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
      // 이 검사는 열기만 본다.
    }

    @Override
    public void close() {
      // 이 검사는 열기만 본다.
    }

    List<Long> gaps() {
      return gaps;
    }

    /** 선로에 실린 순서. 단조 증가여야 한다. */
    List<Long> sentIds() {
      return received.stream().map(MessageEvent::messageId).toList();
    }
  }
}
