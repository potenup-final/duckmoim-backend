package com.duckmoim.chat.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.chat.domain.MessageEvent;
import com.duckmoim.chat.domain.MessageStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 선로 형식 (CH-10 · CH-12).
 *
 * <p><b>재연결 위치가 이 형식에 달려 있다.</b> 브라우저는 마지막으로 받은 {@code id:} 를 {@code Last-Event-ID} 로 돌려주는데, 그 줄을
 * 싣는지 마는지는 이 클래스만 안다 — 스트림 서비스 테스트의 대역은 선로를 거치지 않아 여기서 본다.
 *
 * <p><b>스프링을 띄우지 않는다.</b> {@code SseEmitter} 가 내보내려던 조각을 가로채 문자열로 합쳐 보면 충분하다.
 */
@DisplayName("SSE 선로 형식")
class SseChatStreamSessionTest {

  private final CapturingEmitter emitter = new CapturingEmitter();
  private final SseChatStreamSession session = new SseChatStreamSession(emitter);

  /** 새 메시지의 번호가 곧 재연결 위치다 (CH-11). */
  @DisplayName("새 메시지는 메시지 번호를 id 로 싣는다.")
  @Test
  void send_carriesMessageIdAsEventId() {
    session.send(event(51L, MessageStatus.ACTIVE));

    assertThat(emitter.wire()).contains("id:51").contains("event:message");
  }

  /**
   * <b>지운 메시지는 옛 번호다</b> (STAR-147).
   *
   * <pre>
   * 받은 것      id:50 → id:51 → id:12(삭제)
   * 재연결 위치  12   ← 51 까지 받았는데 13 부터 다시 받는다
   * </pre>
   */
  @DisplayName("상태 변경은 id 없이 message 사건으로 나간다.")
  @Test
  void sendChanged_omitsEventId() {
    session.sendChanged(event(12L, MessageStatus.DELETED));

    assertThat(emitter.wire()).doesNotContain("id:").contains("event:message");
  }

  private static MessageEvent event(long messageId, MessageStatus status) {
    return new MessageEvent(
        messageId, 3L, 7L, "덕후1", null, null, null, status, LocalDateTime.of(2026, 10, 2, 11, 10));
  }

  /** 보내려던 조각을 모은다. 데이터 객체는 문자열이 아니라 건너뛰고, 선로 머리줄만 본다. */
  private static final class CapturingEmitter extends SseEmitter {

    private final List<String> parts = new ArrayList<>();

    @Override
    public void send(SseEventBuilder builder) {
      builder.build().stream()
          .map(DataWithMediaType::getData)
          .filter(String.class::isInstance)
          .map(String.class::cast)
          .forEach(parts::add);
    }

    String wire() {
      return parts.stream().collect(Collectors.joining());
    }
  }
}
