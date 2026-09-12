package com.duckmoim.chat.infra;

import com.duckmoim.chat.domain.MessageEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link MessageEvent} 를 팬아웃 통로에 실을 문자열로 바꾼다 (CH-10).
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
public class MessageEventCodec {

  private final ObjectMapper objectMapper;

  /** 실패하면 {@code null} 이다. 부르는 쪽이 발행을 건너뛴다. */
  public String encode(MessageEvent event) {
    try {
      return objectMapper.writeValueAsString(event);
    } catch (JsonProcessingException e) {
      // 본문을 로그에 남기지 않는다. 어느 메시지였는지만 남긴다.
      log.warn("[MessageEventCodec.encode] 팬아웃 직렬화 실패 messageId={}", event.messageId());
      return null;
    }
  }

  /** 실패하면 {@code null} 이다. 부르는 쪽이 그 한 건만 버린다. */
  public MessageEvent decode(String payload) {
    try {
      return objectMapper.readValue(payload, MessageEvent.class);
    } catch (JsonProcessingException e) {
      log.warn("[MessageEventCodec.decode] 팬아웃 판독 실패 cause={}", e.getClass().getSimpleName());
      return null;
    }
  }
}
