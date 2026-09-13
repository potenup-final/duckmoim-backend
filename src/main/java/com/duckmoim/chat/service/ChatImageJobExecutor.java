package com.duckmoim.chat.service;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 채팅 이미지 작업을 도는 전용 스레드 (CH-16 · CH-17 리뷰).
 *
 * <p><b>배치 스케줄러({@code taskScheduler})에서 돌리지 않는다.</b> 그쪽은 풀이 2인데 {@code @Scheduled} 가 일곱이고, 그중 하나가
 * SSE 하트비트다. 지금까지의 배치는 DB 만 짧게 썼지만 이미지 작업은 <b>한 장마다 S3 에서 최대 10MB 를 받고 올린다.</b>
 *
 * <pre>
 * 배포 직후   스레드 1  EXIF 워커 — V705 기본값 PENDING 이라 기존 사진 전부를 차례로 처리 (몇 분)
 *            스레드 2  모집 마감 배치
 *            하트비트  발화할 스레드가 없다
 * +60초      keep-alive 가 안 나가 ALB 유휴 타임아웃에 SSE 가 무더기로 끊김 → 재연결 폭주
 * </pre>
 *
 * <p>S3 가 느린 날에도 같다. #145 가 하트비트의 Redis 호출을 스케줄러 밖으로 뺀 것과 같은 종류다 ({@code
 * ChatStreamHeartbeatExecutor}).
 *
 * <p><b>{@code @Scheduled} 는 넘기기만 한다.</b> 스케줄러 스레드는 밀리초 만에 풀려 하트비트가 제때 발화한다.
 *
 * <p><b>스레드가 둘인 것은 작업이 둘이라서다</b> — EXIF 워커와 고아 정리. 한 스레드를 나눠 쓰면 새벽 고아 정리(S3 삭제 수천 건)가 그동안 EXIF 처리를
 * 막아 사진이 오래 안 보인다.
 *
 * <p><b>같은 작업이 겹치지 않는다.</b> 스프링의 {@code @Scheduled} 는 앞 회차가 끝나야 다음을 부르지만, 여기로 넘기는 순간 그 보장이 사라진다 —
 * 그래서 작업마다 「돌고 있나」를 들고 와 돌고 있으면 이번 회차를 건너뛴다. 쌓아 두지 않는 것은 다음 주기에 어차피 다시 집기 때문이다.
 */
@Slf4j
@Component
public class ChatImageJobExecutor implements DisposableBean {

  private static final int POOL_SIZE = 2;

  /**
   * 종료 때 기다리는 시간. 한 장을 마저 처리할 만큼이다.
   *
   * <p>못 끝내고 끊겨도 안전하다 — EXIF 는 리스가 풀리면 다시 집고, 고아 정리는 {@code DELETING} 으로 남은 행을 다음 주기가 이어받는다.
   */
  private static final int SHUTDOWN_WAIT_SECONDS = 10;

  private final ThreadPoolTaskExecutor executor = create();

  /**
   * 그 작업의 앞 회차가 끝났을 때만 넘긴다.
   *
   * @param running 작업마다 하나씩 들고 있는 「돌고 있나」 표시
   * @return 넘겼으면 {@code true}. 앞 회차가 아직 돌아 건너뛰었으면 {@code false}
   */
  public boolean submitIfIdle(AtomicBoolean running, Runnable job) {
    if (!running.compareAndSet(false, true)) {
      return false;
    }

    try {
      executor.execute(
          () -> {
            try {
              job.run();
            } finally {
              running.set(false);
            }
          });
      return true;
    } catch (TaskRejectedException rejected) {
      // 종료 중이다. 표시를 되돌려 두지 않으면 다음 기동까지 영원히 건너뛴다.
      running.set(false);
      log.warn("[ChatImageJobExecutor.submitIfIdle] 작업을 넘기지 못했다 — 종료 중으로 보인다.");
      return false;
    }
  }

  @Override
  public void destroy() {
    executor.shutdown();
  }

  private static ThreadPoolTaskExecutor create() {
    ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
    pool.setCorePoolSize(POOL_SIZE);
    pool.setMaxPoolSize(POOL_SIZE);
    // 큐를 두지 않는다. 작업마다 겹치지 않게 막으므로 동시에 둘을 넘을 일이 없다.
    pool.setQueueCapacity(0);
    pool.setThreadNamePrefix("duckmoim-image-job-");
    pool.setWaitForTasksToCompleteOnShutdown(true);
    pool.setAwaitTerminationSeconds(SHUTDOWN_WAIT_SECONDS);
    pool.initialize();
    return pool;
  }
}
