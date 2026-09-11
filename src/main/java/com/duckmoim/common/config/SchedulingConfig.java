package com.duckmoim.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 주기 작업을 켠다.
 *
 * <p>애플리케이션 클래스에 {@code @EnableScheduling} 을 붙이지 않은 이유 — <b>스케줄링이 켜졌다는 사실이 config 에 보여야 다음 배치가 찾을
 * 자리가 생긴다.</b> {@code ClockConfig} · {@code CorsConfig} 와 같은 판단이다.
 *
 * <p>이것으로 도는 것은 둘이다 — 만남시각 경과 마감(PO-14)과 알림 발송 워커(NT-02).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

  /**
   * 배치 수만큼 둔다.
   *
   * <p>기본 스케줄러는 스레드 하나라 작업이 둘이 되면 서로 밀린다. 알림 워커가 10초 주기라 5분 주기인 마감 배치의 차례를 계속 가져가고, 반대로 마감이 밀린 글을
   * 청크로 훑는 동안 알림이 그만큼 늦는다.
   *
   * <p><b>같은 작업이 자기와 겹치는 것은 이 값과 무관하다.</b> 스프링은 한 {@code @Scheduled} 메서드를 동시에 두 번 돌리지 않는다 — 주기가 짧아도
   * 이전 실행이 끝난 뒤에 다음이 잡힌다.
   */
  private static final int POOL_SIZE = 2;

  /** 종료 때 돌던 청크를 마칠 시간. 넘기면 끊는다. */
  private static final int SHUTDOWN_WAIT_SECONDS = 20;

  /**
   * 빈 이름이 {@code taskScheduler} 여야 한다. 스프링이 그 이름으로 찾아 쓰고, 없으면 스레드 하나짜리 기본값으로 돌아간다 — 이름을 바꾸면 이 설정이
   * 조용히 무시된다.
   *
   * <p>배포가 blue-green 이라 종료가 잦다. 돌던 청크를 마치게 두는 것은 트랜잭션 때문이 아니라 (그쪽은 원자적이다) 반쯤 처리된 주기를 로그에서 사고로 읽지
   * 않기 위해서다.
   */
  @Bean
  public ThreadPoolTaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    scheduler.setPoolSize(POOL_SIZE);
    scheduler.setThreadNamePrefix("duckmoim-batch-");
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(SHUTDOWN_WAIT_SECONDS);

    return scheduler;
  }
}
