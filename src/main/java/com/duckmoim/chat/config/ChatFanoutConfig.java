package com.duckmoim.chat.config;

import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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

  /** 평상시 사건 처리는 밀리초라 몇 개면 충분하다. */
  private static final int FANOUT_CORE_POOL_SIZE = 4;

  /** 상한. t3.medium 한 대에서 스레드가 무한히 늘지 않게 막는 값이다. */
  private static final int FANOUT_MAX_POOL_SIZE = 16;

  /** 상한에 닿기 전에 잠깐 밀린 것을 담는다. */
  private static final int FANOUT_QUEUE_CAPACITY = 500;

  private static final int FANOUT_SHUTDOWN_WAIT_SECONDS = 10;

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
    container.setTaskExecutor(fanoutExecutor());

    return container;
  }

  /**
   * 받은 사건을 처리할 스레드 (PR 리뷰).
   *
   * <p><b>안 주면 {@code SimpleAsyncTaskExecutor} 가 붙는다 — 사건 하나마다 새 OS 스레드를 만들고 동시성 상한이 없다.</b> 바이트코드로
   * 확인했다 ({@code RedisMessageListenerContainer} 의 기본 생성자가 그 타입을 만든다).
   *
   * <p>평소에는 문제가 안 보인다. 사건 처리가 밀리초로 끝나기 때문이다. <b>정체된 연결 하나가 생기는 순간 달라진다</b> — 모바일은 화면이 잠기거나 지하철에
   * 들어가면 소켓이 죽지 않고 송신 버퍼만 찬다. 그때 {@code emitter.send} 가 소켓 타임아웃까지 반환하지 않고, 그 방에 오는 메시지마다 새 스레드가 떠서
   * 같은 {@code writeLock} 앞에 줄을 선다.
   *
   * <pre>
   *  2 msg/s × 60초 정체  →  스레드 120개
   * 10 msg/s × 60초 정체  →  스레드 600개
   * </pre>
   *
   * <p><b>상한을 두면 그 줄이 큐로 바뀐다.</b> 스레드가 쌓이는 대신 큐가 차고, 큐가 넘치면 {@code CallerRunsPolicy} 가 구독 스레드에서 직접
   * 처리해 <b>받는 속도를 자연히 늦춘다</b> — 버리는 것보다 늦는 편이 채팅에서는 낫다.
   *
   * <p><b>배치 스케줄러({@code taskScheduler})를 빌려 쓰지 않는다.</b> 그쪽은 풀이 2인데 {@code @Scheduled} 가 일곱이라, 여기서
   * 하나를 오래 잡으면 알림 워커가 밀린다.
   */
  private ThreadPoolTaskExecutor fanoutExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    executor.setCorePoolSize(FANOUT_CORE_POOL_SIZE);
    executor.setMaxPoolSize(FANOUT_MAX_POOL_SIZE);
    executor.setQueueCapacity(FANOUT_QUEUE_CAPACITY);
    executor.setThreadNamePrefix("duckmoim-fanout-");
    // 큐가 넘치면 버리지 않고 구독 스레드가 직접 처리한다. 채팅에서 메시지를 버리는 것은
    // 늦는 것보다 나쁘고, 그 순간 받는 속도가 자연히 느려져 압력이 풀린다.
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(FANOUT_SHUTDOWN_WAIT_SECONDS);
    executor.initialize();

    return executor;
  }
}
