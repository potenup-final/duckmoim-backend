package com.duckmoim.chat.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * <b>Redis 가 죽어도 서비스는 산다</b>를 못박는다 (STAR-110 의 본론).
 *
 * <p>이 티켓이 정한 규칙은 한 줄이다 — <b>멈춰도 되는 것은 실시간 팬아웃뿐이고, 메시지 전송·조회와 헬스체크는 절대 멈추면 안 된다.</b> 규칙을 문서에만 적으면
 * 다음 사람이 {@code publish} 에 {@code throws} 를 하나 더하는 것으로 조용히 깨뜨린다.
 *
 * <p><b>1번 포트를 쓴다.</b> 기본값인 6380 을 그대로 두면 개발자 PC 에 Redis 가 떠 있을 때 연결이 성공해서, <b>실패 경로를 한 번도 지나지 않고
 * 초록불</b>이 된다. 1번은 어디서도 듣지 않아 즉시 거절된다.
 *
 * <p>팬아웃이 실제로 동작하는지는 {@link RedisChatFanoutTest} 가 실물 Redis 로 본다. 여기는 <b>없을 때</b>만 본다.
 */
@SpringBootTest(properties = "spring.data.redis.port=1")
@AutoConfigureMockMvc
@DisplayName("Redis 부재")
class RedisAbsentTest {

  @Autowired private ChatFanout chatFanout;
  @Autowired private MockMvc mockMvc;
  @Autowired private HealthContributorRegistry healthContributorRegistry;

  /**
   * 이 테스트 클래스가 뜬 것 자체가 첫 번째 단언이다 — 컨텍스트가 Redis 없이 기동했다.
   *
   * <p>가능한 이유는 {@code RedisMessageListenerContainer} 가 리스너가 하나라도 붙어야 연결하기 때문이다. 기동 직후에는 구독자가 없어서 연결
   * 시도 자체가 없다.
   */
  @Test
  @DisplayName("Redis 가 없어도 팬아웃 발행이 예외를 던지지 않는다.")
  void publishSwallowsFailure() {
    assertThatCode(() -> chatFanout.publish(1L, "무시될 사건")).doesNotThrowAnyException();
  }

  /**
   * <b>이 단언이 전면 503 을 막는다.</b>
   *
   * <p>Actuator 는 Redis 를 자동으로 헬스 기여자에 넣는다. 그대로 두면 Redis 한 대가 죽는 순간 두 인스턴스가 동시에 DOWN 이 되고, ALB 의
   * healthy 대상이 0 이 되어 전 요청이 503 이 된다 — DB 도 앱도 멀쩡한데 그렇다. {@code diskspace} 를 끈 것과 같은 근거다.
   */
  @Test
  @DisplayName("Redis 가 없어도 헬스체크는 UP 이다.")
  void healthStaysUpWithoutRedis() throws Exception {
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  /**
   * 위 단언의 짝이다. {@code UP} 만 보면 <b>Redis 가 살아 있어서 UP</b> 인 경우와 구분되지 않는다 — 기여자 목록에 없다는 것이 「판정에 쓰이지
   * 않는다」의 직접 증거다.
   */
  @Test
  @DisplayName("헬스체크는 Redis 를 검사 항목으로 갖지 않는다.")
  void healthDoesNotCheckRedis() {
    assertThat(healthContributorRegistry.stream().map(contributor -> contributor.getName()))
        .doesNotContain("redis");
  }
}
