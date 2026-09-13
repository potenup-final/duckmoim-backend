package com.duckmoim.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.duckmoim.chat.domain.ChatImageStorage;
import com.duckmoim.chat.infra.exif.ImageMetadataStripper;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>{@code @Scheduled} 가 붙은 메서드는 넘기기만 한다</b> (CH-16 리뷰).
 *
 * <p>{@code ChatImageJobExecutorTest} 는 전용 스레드가 제대로 도는지를 보고, 여기는 <b>두 작업이 실제로 그 스레드로 넘기는지</b>를 본다 —
 * 누가 {@code scheduleStripping} 안에서 본문을 바로 부르게 되돌려도 실행기 검사는 그대로 통과한다.
 *
 * <p>본문의 첫 DB 호출을 붙잡아 두고, 스케줄러가 부르는 메서드가 그동안 <b>바로 돌아오는지</b>와 본문이 <b>어느 스레드에서 도는지</b>를 잰다.
 */
@DisplayName("채팅 이미지 작업의 스케줄링")
class ChatImageJobSchedulingTest {

  private final ChatImageJobExecutor jobExecutor = new ChatImageJobExecutor();
  private final CountDownLatch release = new CountDownLatch(1);
  private final AtomicReference<String> bodyThread = new AtomicReference<>();

  @AfterEach
  void close() {
    release.countDown();
    jobExecutor.destroy();
  }

  @DisplayName("EXIF 워커의 스케줄 메서드는 S3 작업을 기다리지 않고 돌아온다.")
  @Test
  void exifWorker_offloadsToDedicatedThread() {
    ChatImageExifService service = mock(ChatImageExifService.class);
    given(service.claimProcessableIds(any(), anyInt()))
        .willAnswer(invocation -> blockAndRecord(List.of()));

    ChatImageExifWorker worker =
        new ChatImageExifWorker(
            service,
            mock(ChatImageStorage.class),
            new ImageMetadataStripper(),
            jobExecutor,
            Clock.systemUTC(),
            10);

    assertReturnsImmediately(worker::scheduleStripping);
  }

  @DisplayName("고아 정리의 스케줄 메서드는 S3 작업을 기다리지 않고 돌아온다.")
  @Test
  void cleanupBatch_offloadsToDedicatedThread() {
    ChatImageCleanupService service = mock(ChatImageCleanupService.class);
    given(service.claimChunk(any(), anyInt()))
        .willAnswer(invocation -> blockAndRecord(new ClaimedChatImages(0, List.of())));

    ChatImageCleanupBatch batch =
        new ChatImageCleanupBatch(
            service,
            mock(ChatImageStorage.class),
            jobExecutor,
            Clock.systemUTC(),
            Duration.ofHours(24),
            200);

    assertReturnsImmediately(batch::scheduleCleanup);
  }

  private void assertReturnsImmediately(Runnable scheduledMethod) {
    long started = System.nanoTime();
    scheduledMethod.run();
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    // 본문은 release 전까지 붙잡혀 있다. 스케줄러가 부르는 쪽이 그것을 기다리면 5초가 걸린다.
    assertThat(elapsedMillis).isLessThan(500);
    Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> bodyThread.get() != null);
    assertThat(bodyThread.get()).startsWith("duckmoim-image-job-");
  }

  private <T> T blockAndRecord(T result) throws InterruptedException {
    bodyThread.set(Thread.currentThread().getName());
    release.await(5, TimeUnit.SECONDS);
    return result;
  }
}
