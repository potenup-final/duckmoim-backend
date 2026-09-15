package com.duckmoim.notification.service;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 웹 푸시를 보내는 전용 스레드 (NT-13 · STAR-149).
 *
 * <p><b>워커 주기가 푸시를 기다리지 않게 하려고 있다.</b> 한 주기가 알림함 INSERT 와 푸시를 한 줄로 처리하던 때는 푸시 서비스가 느린 날 뒤에 선 모든 사람의
 * 알림함이 함께 늦었다 (QA-FAIL-04 실측: 느린 푸시 5건 뒤 49초).
 *
 * <pre>
 * 전  주기  [알림함 → 푸시 10초] [알림함 → 푸시 10초] [알림함 → …   ← 알림함이 푸시를 기다린다
 * 후  주기  [알림함 × 청크] → 자리 나는 대로 넘김                   ← 알림함은 먼저 끝난다
 *     일꾼        [푸시] [푸시] [푸시] …
 * </pre>
 *
 * <h2>자리가 날 때까지 기다리되, 마감까지만</h2>
 *
 * <p><b>곧바로 거절하면 평소에도 푸시가 늦는다</b> (STAR-149 리뷰). 넘기는 순간은 주기 루프 안뿐인데 그 루프는 몇 ms 만에 끝난다. 일꾼이 모두 바쁘다고
 * 바로 거절하면, 채팅방 메시지 하나로 알림 99건이 생긴 날 95건이 리스(30초)에 묶여 <b>푸시 서비스가 멀쩡한데도 30~40초 늦게</b> 나갔다.
 *
 * <p><b>무한정 기다리지는 않는다.</b> 기다리는 동안에도 워커 리스는 흐르고, 넘긴 뒤에도 발송이 제한 시간(10초)만큼 걸린다. 둘을 합친 시간이 리스를 넘기면 다른
 * 인스턴스가 같은 건을 다시 집어 <b>같은 푸시가 두 번 울린다</b> (NT-04). 그래서 부르는 쪽이 마감을 주고, 그때까지 자리가 안 나면 넘기지 않는다 — 그 건은
 * 리스가 풀린 뒤 다시 집힌다.
 *
 * <p><b>허가(세마포어)로 「실행 중 + 대기 중」을 일꾼 수 이하로 묶는다.</b> 대기열 없이 스레드에 바로 넘기면 스레드가 막 일을 끝내고 다음 일을 받기 직전의 틈에
 * 거절될 수 있다. 허가를 먼저 얻고 넘기므로 대기열에 들어가는 일은 일꾼 수를 넘지 않고, 거절은 종료 중일 때뿐이다.
 *
 * <p><b>배치 스케줄러({@code taskScheduler})에서 보내지 않는다.</b> 그쪽은 풀이 2이고 {@code @Scheduled} 가 여럿이라, 느린 푸시가
 * 스레드를 쥐면 하트비트 · 마감 배치까지 밀린다 — {@code ChatImageJobExecutor} · {@code ChatStreamHeartbeatExecutor} 가
 * 같은 이유로 따로 있다.
 */
@Component
public class NotificationPushExecutor implements DisposableBean {

  /**
   * 종료 때 기다리는 시간. 한 건의 발송 제한 시간(10초)을 마저 쓸 만큼이다.
   *
   * <p>못 끝내고 끊겨도 안전하다 — 그 건은 리스가 풀린 뒤 다시 집히고, 알림함은 이미 있어 푸시만 다시 보낸다 (ADR 0006 의 at-least-once).
   */
  private static final int SHUTDOWN_WAIT_SECONDS = 15;

  private final ThreadPoolTaskExecutor executor;

  /** 일꾼 한 명당 허가 하나. 넘긴 일이 끝나면 돌려준다. */
  private final Semaphore seats;

  /**
   * @param poolSize 인스턴스 하나가 동시에 보내는 건수
   */
  public NotificationPushExecutor(
      @Value("${duckmoim.notification.worker.push-pool-size}") int poolSize) {
    this.executor = create(poolSize);
    this.seats = new Semaphore(poolSize);
  }

  /**
   * 일꾼 자리가 날 때까지 {@code maxWait} 만큼 기다렸다가 넘긴다.
   *
   * @param maxWait 기다릴 수 있는 최대 시간. 0 이하면 기다리지 않는다
   * @return 넘겼으면 {@code true}. 그 사이 자리가 안 났거나 종료 중이라 못 넘겼으면 {@code false}
   */
  public boolean submitWithin(Runnable push, Duration maxWait) {
    if (!acquire(maxWait)) {
      return false;
    }

    try {
      executor.execute(
          () -> {
            try {
              push.run();
            } finally {
              seats.release();
            }
          });
      return true;
    } catch (TaskRejectedException rejected) {
      // 종료 중이다. 허가를 돌려주지 않으면 그 자리가 영영 비지 않는다.
      seats.release();
      return false;
    }
  }

  private boolean acquire(Duration maxWait) {
    try {
      return seats.tryAcquire(Math.max(0, maxWait.toNanos()), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      // 스케줄러가 내려가는 중이다. 신호를 되살리고 넘기지 않는다 — 리스가 풀린 뒤 다시 집힌다.
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public void destroy() {
    executor.shutdown();
  }

  private static ThreadPoolTaskExecutor create(int poolSize) {
    ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
    pool.setCorePoolSize(poolSize);
    pool.setMaxPoolSize(poolSize);
    // 허가가 일꾼 수만큼만 있어 대기열에 서는 일은 이보다 많아지지 않는다 (클래스 각주).
    pool.setQueueCapacity(poolSize);
    pool.setThreadNamePrefix("duckmoim-push-");
    pool.setWaitForTasksToCompleteOnShutdown(true);
    pool.setAwaitTerminationSeconds(SHUTDOWN_WAIT_SECONDS);
    pool.initialize();
    return pool;
  }
}
