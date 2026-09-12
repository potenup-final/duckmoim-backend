package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.MessageEvent;
import java.util.List;

/**
 * 끊겨 있던 동안 못 받은 것 (CH-11).
 *
 * <p><b>「없다」와 「너무 많다」가 다른 답이다.</b> 둘 다 {@link #events} 가 비어 있지만 앞은 선로에 아무것도 쓰지 않고, 뒤는 클라이언트에게 <b>목록
 * API 로 따라잡으라</b>고 알려야 한다. 빈 목록 하나로 합치면 그 구분이 사라지고, 며칠 끊겼던 클라이언트가 <b>아무 일도 없었던 것처럼</b> 이어 붙는다.
 *
 * @param events 오래된 것부터다. 선로에 실리는 순서가 곧 이 순서다
 * @param truncated 상한을 넘어 재전송을 포기했나. 참이면 {@code events} 는 비어 있다
 */
public record MissedMessages(List<MessageEvent> events, boolean truncated) {

  static MissedMessages of(List<MessageEvent> events) {
    return new MissedMessages(events, false);
  }

  /** 너무 많이 밀렸다. 되돌려주지 않고 알리기만 한다. */
  static MissedMessages tooMany() {
    return new MissedMessages(List.of(), true);
  }

  boolean isEmpty() {
    return events.isEmpty() && !truncated;
  }
}
