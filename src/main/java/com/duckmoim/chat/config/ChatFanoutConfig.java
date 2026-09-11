package com.duckmoim.chat.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 팬아웃 구독을 받아 줄 컨테이너를 띄운다 (CH-10).
 *
 * <p>Spring Boot 가 {@code StringRedisTemplate} 은 자동으로 만들어 주지만 {@link RedisMessageListenerContainer}
 * 는 만들지 않는다. 구독하는 쪽이 직접 선언해야 한다.
 *
 * <p><b>기동을 막지 않는다.</b> 이 컨테이너는 리스너가 하나라도 등록돼야 Redis 에 연결한다. 기동 직후에는 구독자가 없으므로 Redis 가 죽어 있어도 연결
 * 시도가 일어나지 않고, 따라서 컨텍스트가 뜨는 것을 막지 않는다. 연결이 선 뒤에 끊기면 컨테이너가 스스로 재시도한다.
 */
@Slf4j
@Configuration
public class ChatFanoutConfig {

  @Bean
  public RedisMessageListenerContainer redisMessageListenerContainer(
      RedisConnectionFactory connectionFactory,
      @Value("${spring.data.redis.host}") String host,
      @Value("${spring.data.redis.port}") int port) {

    // 연결 정보를 기동 때 한 줄 남긴다.
    //
    // **없으면 기동을 실패시키는 쪽을 고르지 않았기 때문에 필요하다.** application-prod.yml 의
    // 다른 여덟 개는 비면 기동이 죽지만 Redis 는 없어도 본 기능이 돌아야 하는 부속이라
    // 기본값(localhost)을 남겼다. 그 대가로 REDIS_HOST 를 빠뜨리면 「팬아웃만 조용히 안 되는
    // 정상 기동」이 되는데, 이 줄이 그때 로그에서 바로 드러나게 한다.
    log.info("[ChatFanoutConfig.redisMessageListenerContainer] 팬아웃 대상 host={} port={}", host, port);

    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    return container;
  }
}
