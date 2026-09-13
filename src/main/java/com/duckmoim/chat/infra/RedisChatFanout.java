package com.duckmoim.chat.infra;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * {@link ChatFanout} 을 Redis Pub/Sub 으로 구현한다.
 *
 * <p><b>방마다 채널 하나다.</b> 채널 하나에 전부 실어 보내고 구독자가 걸러내는 방법도 있지만, 그러면 모든 인스턴스가 모든 방의 메시지를 받아서 방 수에 비례해
 * 쓸모없는 트래픽이 는다. 채널을 가르면 Redis 가 걸러 준다.
 *
 * <p><b>예외를 삼킨다.</b> {@link ChatFanout} 의 계약이다 — Redis 장애가 메시지 전송의 500 이 되면 안 된다. 삼키되 <b>로그는
 * 남긴다</b> — 조용히 실패하면 「실시간이 안 되는데 아무도 모르는」 상태가 되고, 그것이 관측성을 붙여 둔 이유다.
 *
 * <p><b>본문을 로그에 남기지 않는다.</b> 발행 실패를 찍을 때 payload 를 함께 찍으면 메시지 본문이 그대로 로그로 나간다. 방 번호와 예외 종류까지만 남긴다.
 */
@Slf4j
@Component
public class RedisChatFanout implements ChatFanout {

  /** {@code chat:room:42} 가 42번 방의 채널이다. */
  private static final String CHANNEL_PREFIX = "chat:room:";

  private final StringRedisTemplate redisTemplate;
  private final RedisMessageListenerContainer listenerContainer;

  public RedisChatFanout(
      StringRedisTemplate redisTemplate, RedisMessageListenerContainer listenerContainer) {
    this.redisTemplate = redisTemplate;
    this.listenerContainer = listenerContainer;
  }

  @Override
  public void publish(long roomId, String payload) {
    try {
      redisTemplate.convertAndSend(channelOf(roomId), payload);
    } catch (RuntimeException e) {
      // 부르는 쪽은 이미 MySQL 에 저장을 마쳤다. 여기서 던지면 그 저장까지 롤백된다.
      log.warn(
          "[RedisChatFanout.publish] 팬아웃 발행 실패 — 실시간 전달만 건너뛴다. roomId={} cause={}",
          roomId,
          e.getClass().getSimpleName());
    }
  }

  @Override
  public ChatFanoutSubscription subscribe(long roomId, Consumer<String> handler) {
    ChannelTopic topic = ChannelTopic.of(channelOf(roomId));
    MessageListener listener =
        (message, pattern) -> handler.accept(new String(message.getBody(), StandardCharsets.UTF_8));

    // 컨테이너는 리스너가 하나라도 붙어야 Redis 에 연결한다. 그래서 아무도 구독하지 않는
    // 기동 직후에는 Redis 가 죽어 있어도 연결 시도 자체가 없다.
    listenerContainer.addMessageListener(listener, topic);

    return () -> listenerContainer.removeMessageListener(listener, topic);
  }

  private String channelOf(long roomId) {
    return CHANNEL_PREFIX + roomId;
  }
}
