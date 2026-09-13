package com.duckmoim.chat.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 팬아웃이 <b>실제로 건너간다</b>를 실물 Redis 로 본다 (CH-10).
 *
 * <p><b>인스턴스 둘을 세우지 않고도 증명이 되는 이유.</b> 이 기능이 푸는 문제는 「발행한 쪽과 받는 쪽이 같은 JVM 이 아니다」인데, Pub/Sub 은 발행자와
 * 구독자가 같은 프로세스인지 아닌지를 구분하지 않는다. 메시지가 <b>Redis 를 한 바퀴 돌아서</b> 오는 것만 확인하면 인스턴스가 갈려도 같은 경로다.
 *
 * <p><b>Redis 컨테이너를 여기서만 띄운다.</b> {@code ChatFanout} 인터페이스를 둔 덕에 나머지 테스트 전부가 Redis 없이 돈다 — 팬아웃을 쓰는
 * 코드가 이 포트에만 의존하기 때문이다. 인터페이스가 layering 격식이 아니라 테스트 비용을 줄이는 자리다.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Redis 팬아웃")
class RedisChatFanoutTest {

  private static final long ROOM = 42L;
  private static final long OTHER_ROOM = 43L;

  /** 로컬 compose 와 같은 판본을 쓴다. 다르면 여기서 통과한 것이 운영에서 통과한다는 보장이 없다. */
  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

  @DynamicPropertySource
  static void redisConnection(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @Autowired private ChatFanout chatFanout;

  @Test
  @DisplayName("발행한 사건이 같은 방을 구독한 쪽에 도착한다.")
  void publishReachesSubscriberOfSameRoom() throws Exception {
    BlockingQueue<String> received = new LinkedBlockingQueue<>();

    try (ChatFanoutSubscription subscription = chatFanout.subscribe(ROOM, received::add)) {
      chatFanout.publish(ROOM, "건너간 사건");

      assertThat(received.poll(5, TimeUnit.SECONDS)).isEqualTo("건너간 사건");
    }
  }

  /**
   * 방마다 채널을 가른 것이 실제로 갈렸는지 본다.
   *
   * <p>한 채널에 전부 실어 보내고 구독자가 걸러내는 구현도 이 테스트만으로는 통과할 수 있으므로, 여기서 보는 것은 「남의 방 메시지가 이 핸들러까지 오지 않는다」
   * 하나다. 그것이 방 수에 비례해 트래픽이 느는 것을 막는 조건이다.
   */
  @Test
  @DisplayName("다른 방에 발행한 사건은 도착하지 않는다.")
  void publishDoesNotReachOtherRoom() throws Exception {
    BlockingQueue<String> received = new LinkedBlockingQueue<>();

    try (ChatFanoutSubscription subscription = chatFanout.subscribe(ROOM, received::add)) {
      chatFanout.publish(OTHER_ROOM, "남의 방 사건");

      assertThat(received.poll(1, TimeUnit.SECONDS)).isNull();
    }
  }

  /** 닫지 않으면 SSE 가 끝난 뒤에도 리스너가 남아 방마다 쌓인다. 끊기는 것이 계약이라 검사한다. */
  @Test
  @DisplayName("구독을 닫으면 그 뒤에 발행한 사건은 도착하지 않는다.")
  void closedSubscriptionStopsReceiving() throws Exception {
    BlockingQueue<String> received = new LinkedBlockingQueue<>();

    ChatFanoutSubscription subscription = chatFanout.subscribe(ROOM, received::add);
    subscription.close();

    chatFanout.publish(ROOM, "닫은 뒤의 사건");

    assertThat(received.poll(1, TimeUnit.SECONDS)).isNull();
  }
}
