package com.duckmoim.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 채팅 이미지 작업의 전용 스레드 (CH-16 리뷰).
 *
 * <p><b>지키는 것은 「스케줄러 스레드가 바로 풀린다」 하나다.</b> 배치 스케줄러는 스레드가 둘이고 그중 한 자리를 SSE 하트비트가 제때 얻어야 한다 — 이미지 작업이
 * S3 를 몇 분씩 붙잡으면 하트비트가 발화하지 못해 ALB 유휴 타임아웃에 스트림이 끊긴다.
 */
@DisplayName("채팅 이미지 작업 스레드")
class ChatImageJobExecutorTest {

  private final ChatImageJobExecutor executor = new ChatImageJobExecutor();

  @AfterEach
  void close() {
    executor.destroy();
  }

  /** 작업이 오래 걸려도 넘기는 쪽은 바로 돌아온다. 넘기는 쪽이 곧 스케줄러 스레드다. */
  @DisplayName("작업이 붙잡혀 있어도 넘기는 쪽은 바로 돌아온다.")
  @Test
  void submit_returnsWhileJobIsBlocked() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean running = new AtomicBoolean();

    long started = System.nanoTime();
    boolean submitted = executor.submitIfIdle(running, () -> await(release));
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertThat(submitted).isTrue();
    assertThat(elapsedMillis).isLessThan(500);
    release.countDown();
  }

  /** 스케줄러 스레드(duckmoim-batch-)가 아니라 전용 스레드에서 돈다. */
  @DisplayName("작업은 전용 스레드에서 돈다.")
  @Test
  void submit_runsOnDedicatedThread() {
    AtomicReference<String> thread = new AtomicReference<>();

    executor.submitIfIdle(new AtomicBoolean(), () -> thread.set(Thread.currentThread().getName()));

    Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> thread.get() != null);
    assertThat(thread.get()).startsWith("duckmoim-image-job-");
  }

  /**
   * <b>같은 작업이 겹치지 않는다.</b> 전용 스레드로 넘기면 {@code @Scheduled} 의 「앞 회차가 끝나야 다음」 보장이 사라진다.
   *
   * <p>겹치면 EXIF 워커 둘이 같은 사진을 받거나, 청크 순회가 서로의 결과를 다시 집는다.
   */
  @DisplayName("앞 회차가 돌고 있으면 이번 회차를 건너뛴다.")
  @Test
  void submit_skipsWhilePreviousRunIsInProgress() {
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean running = new AtomicBoolean();
    AtomicInteger runs = new AtomicInteger();

    executor.submitIfIdle(
        running,
        () -> {
          runs.incrementAndGet();
          await(release);
        });
    boolean second = executor.submitIfIdle(running, runs::incrementAndGet);
    release.countDown();

    assertThat(second).isFalse();
    Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> !running.get());
    assertThat(runs.get()).isEqualTo(1);
  }

  /** 끝나면 다음 회차를 받아야 한다. 표시가 안 풀리면 그 작업이 다음 기동까지 영원히 멈춘다. */
  @DisplayName("앞 회차가 끝나면 다음 회차를 받는다.")
  @Test
  void submit_acceptsNextRunAfterCompletion() {
    AtomicBoolean running = new AtomicBoolean();
    executor.submitIfIdle(running, () -> {});

    Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> !running.get());
    assertThat(executor.submitIfIdle(running, () -> {})).isTrue();
  }

  /** 작업이 예외로 끝나도 표시가 풀려야 한다. 안 풀리면 한 번의 예외가 그 작업을 영구히 멈춘다. */
  @DisplayName("작업이 예외로 끝나도 다음 회차를 받는다.")
  @Test
  void submit_releasesAfterFailure() {
    AtomicBoolean running = new AtomicBoolean();
    executor.submitIfIdle(
        running,
        () -> {
          throw new IllegalStateException("S3 끊김");
        });

    Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> !running.get());
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
