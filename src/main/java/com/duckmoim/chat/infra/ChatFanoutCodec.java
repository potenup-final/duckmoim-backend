package com.duckmoim.chat.infra;

import com.duckmoim.chat.domain.MessageEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link ChatFanoutEvent} 를 팬아웃 통로에 실을 문자열로 바꾼다 (CH-10 · CH-04).
 *
 * <p><b>infra 에 사는 이유는 Jackson 때문이다.</b> 직렬화는 기술이고 service 는 「사건을 보낸다」까지만 안다 — {@code ChatFanout} 이
 * {@code RedisTemplate} 을 가린 것과 같은 자리다.
 *
 * <p><b>스프링이 쓰는 {@code ObjectMapper} 를 그대로 주입받는다.</b> 새로 만들면 날짜 모듈 설정이 갈려서, 같은 값이 HTTP 응답과 팬아웃에서 다른
 * 모양으로 나간다.
 *
 * <p><b>실패를 예외로 올리지 않는다.</b> 발행 경로에 있고 {@code ChatFanout} 의 계약이 「팬아웃 실패가 전송을 깨지 않는다」이기 때문이다. 받는 쪽도
 * 마찬가지다 — 판독할 수 없는 한 건이 그 방의 구독 자체를 끊으면 안 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatFanoutCodec {

  private final ObjectMapper objectMapper;

  /** 새 메시지를 싣는다. 실패하면 {@code null} 이고 부르는 쪽이 발행을 건너뛴다. */
  public String encodeMessage(MessageEvent event) {
    try {
      return objectMapper.writeValueAsString(ChatFanoutEvent.message(event));
    } catch (JsonProcessingException e) {
      // 본문을 로그에 남기지 않는다. 어느 메시지였는지만 남긴다.
      log.warn("[ChatFanoutCodec.encodeMessage] 팬아웃 직렬화 실패 messageId={}", event.messageId());
      return null;
    }
  }

  /**
   * 퇴장을 싣는다 (CH-04 · PR #138 리뷰).
   *
   * <p>번호 하나라 직렬화가 실패할 이유가 사실상 없지만, <b>계약을 종류마다 다르게 두지 않는다</b> — 한쪽만 던지면 부르는 쪽이 두 규칙을 기억해야 한다.
   */
  public String encodeMemberLeft(Long userId) {
    try {
      return objectMapper.writeValueAsString(ChatFanoutEvent.memberLeft(userId));
    } catch (JsonProcessingException e) {
      log.warn("[ChatFanoutCodec.encodeMemberLeft] 팬아웃 직렬화 실패 userId={}", userId);
      return null;
    }
  }

  /**
   * 실패하면 {@code null} 이다. 부르는 쪽이 그 한 건만 버린다.
   *
   * <p><b>모르는 {@code type} 도 {@code null} 이다.</b> 열거에 없는 값이면 Jackson 이 여기서 터지고, 그 한 건만 버려진다 — 종류가
   * 느는 날 <b>아직 안 바뀐 인스턴스가 새 사건을 받는 창</b>이 배포마다 열리기 때문이다. 그 창에서 구독 전체가 끊기면 안 된다.
   */
  public ChatFanoutEvent decode(String payload) {
    try {
      return objectMapper.readValue(payload, ChatFanoutEvent.class);
    } catch (JsonProcessingException e) {
      log.warn("[ChatFanoutCodec.decode] 팬아웃 판독 실패 cause={}", e.getClass().getSimpleName());
      return null;
    }
  }
}
