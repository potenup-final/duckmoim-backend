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
