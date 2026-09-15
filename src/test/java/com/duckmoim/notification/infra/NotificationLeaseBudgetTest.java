package com.duckmoim.notification.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * 넘긴 푸시는 워커 리스 안에 끝난다 (NT-04 · STAR-149).
 *
 * <p><b>이 관계가 「워커 2개 동시 구동 시 중복 발송 0건」을 받친다.</b> 발송이 리스보다 길어지면 다른 인스턴스가 만료된 리스를 보고 같은 건을 다시 집어, 같은
 * 푸시가 두 번 울린다.
 *
 * <pre>
 * blue   리스 30초 ────────────────┤ 만료
 *        발송 ───────────────────────────────── 40초
 * green                            └ 같은 건을 다시 집어 발송   ← 두 번 울림
 * </pre>
 *
 * <p><b>세 값이 서로 다른 자리에 산다.</b> 리스와 넘기기 마감은 {@code application.yml} 에, 발송 제한 시간은 발송기 상수에 있어서 한쪽만 바꾸면
 * 컴파일도 다른 검사도 초록불인 채로 조용히 깨진다 — ADR 0008 이 리스를 정할 때 「발송은 밀리초」였던 전제가 푸시로 깨진 것이 바로 그 모양이었다.
 *
 * <p><b>스프링을 띄우지 않는다.</b> 설정 파일을 직접 읽는다 — {@code RedisPortProfileTest} 와 같은 방식이다.
 */
@DisplayName("넘기기 마감 · 발송 제한 시간과 워커 리스")
class NotificationLeaseBudgetTest {

  private static final String LEASE = "duckmoim.notification.worker.lease";

  private static final String HANDOFF_WAIT = "duckmoim.notification.worker.handoff-wait";

  /** 결과 기록(트랜잭션 하나)과 인스턴스 사이 시계 차이를 받을 여유. 넘기기 마감과 발송 제한 시간을 다 쓰고도 이만큼은 리스가 남아야 한다. */
  private static final Duration MARGIN = Duration.ofSeconds(5);

  /**
   * <b>최악의 경로</b> — 청크를 집고 넘기기 마감 직전에 넘긴 건이 발송 제한 시간을 다 쓴다.
   *
   * <pre>
   * 집기(리스 시작) ── 넘기기 마감 15초 ──┬── 발송 제한 10초 ──┤ 25초   ← 리스 30초 안
   *                                     넘김
   * </pre>
   */
  @DisplayName("넘기기 마감과 발송 제한 시간을 다 써도 워커 리스 안에 끝난다.")
  @Test
  void handoffAndSendFitInLease() throws IOException {
    PropertySourcesPropertyResolver config = applicationYml();

    Duration lease = DurationStyle.detectAndParse(config.getProperty(LEASE));
    Duration handoffWait = DurationStyle.detectAndParse(config.getProperty(HANDOFF_WAIT));
    Duration sendTimeout = Duration.ofMillis(WebPushNotificationSender.SEND_TIMEOUT_MILLIS);

    assertThat(handoffWait.plus(sendTimeout).plus(MARGIN)).isLessThanOrEqualTo(lease);
  }

  private static PropertySourcesPropertyResolver applicationYml() throws IOException {
    MutablePropertySources sources = new MutablePropertySources();
    new YamlPropertySourceLoader()
        .load("application.yml", new ClassPathResource("application.yml"))
        .forEach(sources::addLast);
    return new PropertySourcesPropertyResolver(sources);
  }
}
