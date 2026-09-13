package com.duckmoim.chat.presentation;

import com.duckmoim.chat.domain.MessageEvent;
import com.duckmoim.chat.service.ChatStreamSession;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@link ChatStreamSession} 을 {@code SseEmitter} 로 구현한다 (CH-10).
 *
 * <p><b>이 클래스가 SSE 의 선로 형식을 아는 유일한 자리다.</b> service 는 「사건을 민다」까지만 알고, {@code id:} · {@code event:}
 * · {@code data:} 세 줄로 바뀌는 것은 여기서 일어난다.
 *
 * <p><b>{@code id} 가 {@code messageId} 다.</b> 브라우저가 재연결할 때 그 값을 {@code Last-Event-ID} 헤더로 돌려주고, 그것이
 * {@code CH-11}(STAR-114)의 입력이 된다 — {@code MessageCursor} 를 한 값으로 둔 결정이 여기서 값을 한다.
 *
 * <p><b>{@code data} 는 {@link MessageItemResponse} 다.</b> 목록으로 받은 말풍선과 모양이 같아야 클라이언트가 같은 배열에 넣는다
 * (CH-09).
 */
@Slf4j
public class SseChatStreamSession implements ChatStreamSession {

  private final SseEmitter emitter;

  /**
   * <b>쓰기를 직렬화하지 않는다 — 스프링이 이미 한다</b> (PR #142 리뷰에서 바이트코드로 확인).
   *
   * <p>한 연결에 쓰는 스레드가 셋이다.
   *
   * <pre>
   * 요청 스레드     재연결 재전송 (CH-11)
   * 구독 스레드     실시간 팬아웃 (CH-10)
   * 하트비트 풀     30초마다 주석 한 줄
   * </pre>
   *
   * <p>SSE 는 줄 단위 형식이라 둘이 동시에 쓰면 한 사건의 {@code data:} 줄 사이에 다른 사건이 끼어든다. <b>그런데 그 직렬화가 이미 프레임워크 안에
   * 있다</b> — {@code spring-webmvc 6.2.19} 의 바이트코드에 {@code ResponseBodyEmitter.writeLock}({@code
   * ReentrantLock})이 있고 {@code SseEmitter.send(SseEventBuilder)} · {@code
   * ResponseBodyEmitter.send(Set)} · {@code complete()} 가 모두 그것을 잡는다.
   *
   * <p><b>그래서 여기에 락을 하나 더 두었다가 뺐다.</b> 같은 구간을 두 번 잠그는 비용이 아까운 것이 아니라, <b>읽는 사람을 속이는 쪽</b>이 문제다 — 다음
   * 사람이 「여기 직렬화가 필요하다」를 보고 비슷한 자리에 락을 복제한다.
   *
   * <p><b>{@link #close} 가 그 락을 기다린다는 사실은 남는다.</b> {@code complete()} 도 같은 {@code writeLock} 을 잡으므로
   * 정체된 연결에 하트비트가 쓰고 있으면 닫기가 그만큼 기다린다 — 부르는 자리가 트랜잭션 안이라 {@code ChatRoomLeaveService} 에 적어 두었다.
   */
  public SseChatStreamSession(SseEmitter emitter) {
    this.emitter = emitter;
  }

  /**
   * 사건을 선로에 쓴다.
   *
   * <p><b>던지지 않는다.</b> {@link ChatStreamSession} 의 계약이다 — 브라우저를 닫은 순간과 다음 메시지가 오는 순간 사이는 항상 열려 있어서,
   * 이미 죽은 연결에 쓰는 일이 정상 경로에 있다. 그때 예외가 올라가면 <b>한 사람의 죽은 연결이 그 방 전체의 팬아웃을 끊는다.</b>
   *
   * <p>그 연결은 {@code emitter} 의 완료 콜백이 정리한다 — 여기서 목록을 건드리지 않는 이유다.
   */
  @Override
  public void send(MessageEvent event) {
    try {
      emitter.send(
          SseEmitter.event()
              .id(String.valueOf(event.messageId()))
              .name("message")
              .data(MessageItemResponse.from(event)));
    } catch (IOException | IllegalStateException e) {
      // 본문을 로그에 남기지 않는다. 끊긴 연결이라는 사실만 남긴다.
      log.debug(
          "[SseChatStreamSession.send] 끊긴 연결에 밀었다 messageId={} cause={}",
          event.messageId(),
          e.getClass().getSimpleName());
    }
  }

  /**
   * 따라잡으라는 신호를 보낸다 (CH-11).
   *
   * <p><b>{@code id:} 줄을 싣지 않는다.</b> 실으면 브라우저가 다음 재연결에 그 값을 {@code Last-Event-ID} 로 보내고, 그 순간 빠진
   * 구간을 건너뛰고 시작한다 — 되돌려주지 못한 구간이 그대로 굳는다. {@code id} 가 없으면 브라우저는 <b>직전에 받은 말풍선의 번호</b>를 그대로 들고 다시
   * 붙는다.
   */
  @Override
  public void sendGap(Long fromMessageId) {
    try {
      emitter.send(SseEmitter.event().name("gap").data(new StreamGapResponse(fromMessageId)));
    } catch (IOException | IllegalStateException e) {
      log.debug("[SseChatStreamSession.sendGap] 끊긴 연결에 알렸다 cause={}", e.getClass().getSimpleName());
    }
  }

  /**
   * 주석 한 줄을 보낸다.
   *
   * <p>SSE 는 {@code :} 로 시작하는 줄을 주석으로 읽고 {@code EventSource} 가 이벤트로 올리지 않는다 — 화면은 조용하고 선로만 살아 있다.
   *
   * <p><b>쓰기가 실패하는 것이 이 신호의 두 번째 목적이다.</b> 브라우저를 닫아도 서버는 다음에 쓸 때까지 모르는데, 여기서 터지면 {@code emitter} 의
   * 오류 콜백이 돌아 목록에서 빠진다.
   */
  @Override
  public void beat() {
    try {
      emitter.send(SseEmitter.event().comment("keep-alive"));
    } catch (IOException | IllegalStateException e) {
      log.debug("[SseChatStreamSession.beat] 끊긴 연결이다 cause={}", e.getClass().getSimpleName());
    }
  }

  /** 연결을 끝낸다. 이미 끝난 것을 다시 끝내도 조용하다 — 퇴장과 클라이언트 종료가 겹칠 수 있다. */
  @Override
  public void close() {
    try {
      emitter.complete();
    } catch (IllegalStateException e) {
      log.debug("[SseChatStreamSession.close] 이미 끝난 연결이다.");
    }
  }
}
