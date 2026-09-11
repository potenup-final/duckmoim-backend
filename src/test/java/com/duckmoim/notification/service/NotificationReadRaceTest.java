package com.duckmoim.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.notification.infra.NotificationRepository;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 개별 읽음과 전체 읽음이 겹쳐도 <b>처음 읽은 시각이 남는지</b> (NT-09. PR #123 리뷰).
 *
 * <p>한때 개별 읽음이 엔티티를 불러다 {@code readAt != null} 로 판정했다. <b>그 값은 조회 시점에 복사된 스냅숏</b>이라, 조회와 커밋 사이에 전체
 * 읽음이 지나가면 판정이 그대로 통과해 더티 체킹이 먼저 찍힌 시각을 덮어썼다. 화면에 나가지 않는 컬럼이라 (`read` 불리언만 나간다) 눈으로는 끝까지 보이지 않고,
 * 「언제 읽었나」를 되돌아볼 때가 되어서야 값이 틀린 것을 안다.
 *
 * <p>지금은 두 경로가 <b>같은 SQL 가드</b>를 쓴다 — {@code read_at is null}. MySQL 이 잠근 현재 행에 대고 판정하므로 그 창이 없다.
 *
 * <p><b>{@code @Transactional} 을 붙이지 않는다.</b> 트랜잭션 둘이 실제로 갈라져 있어야 이 창이 재현된다 — 테스트에 트랜잭션을 걸면 별도 스레드가
 * 거기 참여하지 않아 검사가 <b>항상 통과하는 상태</b>가 된다 (테스트 컨벤션).
 */
@SpringBootTest
class NotificationReadRaceTest {

  private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 14, 0, 0);

  /** 전체 읽음이 먼저 커밋하는 시각. 개별 읽음이 나중에 와도 이 값이 남아야 한다. */
  private static final LocalDateTime BULK_AT = BASE.plusHours(1);

  private static final long ME = 95_001L;

  private final AtomicLong outboxIds = new AtomicLong(95_000);

  @Autowired private NotificationRepository notificationRepository;
  @Autowired private NotificationReadService notificationReadService;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbc;

  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM notification WHERE recipient_id = ?", ME);
  }

  @DisplayName("개별 읽음이 진행되는 사이 전체 읽음이 지나가도 처음 읽은 시각이 남는다.")
  @Test
  void markRead_overlapsWithMarkAllRead() throws Exception {
    long id = notified(ME);
    CountDownLatch opened = new CountDownLatch(1);
    CountDownLatch bulkCommitted = new CountDownLatch(1);

    // 스레드가 죽어도 join 은 조용히 끝난다. 잡아 두지 않으면 개별 읽음이 404 로 터져도
    // 아래 단언이 그대로 통과해서 검사가 아무것도 증명하지 않는다
    AtomicReference<Throwable> failure = new AtomicReference<>();

    Thread single =
        new Thread(
            () ->
                transactionTemplate.executeWithoutResult(
                    status -> {
                      // 전체 읽음보다 먼저 스냅숏을 뜬다. 여기서 이 행은 아직 안 읽은 상태다
                      notificationRepository.existsByIdAndRecipientId(id, ME);
                      opened.countDown();
                      await(bulkCommitted);

                      // 스냅숏은 낡았지만 UPDATE 는 현재 행을 본다
                      notificationReadService.markRead(ME, id);
                    }));

    single.setUncaughtExceptionHandler((thread, thrown) -> failure.set(thrown));
    single.start();
    await(opened);
    transactionTemplate.executeWithoutResult(
        status -> notificationRepository.markAllRead(ME, BULK_AT));
    bulkCommitted.countDown();
    single.join();

    assertThat(failure.get()).as("개별 읽음이 끝까지 돌아야 한다").isNull();
    assertThat(readAtOf(id))
        .as("먼저 커밋된 전체 읽음의 시각이 남아야 한다 (API-설계.md 「2-10. 알림 (Notification) · 2차」)")
        .isEqualTo(BULK_AT);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).as("상대 트랜잭션을 기다리다 시간이 다 됐다").isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private long notified(long recipientId) {
    jdbc.update(
        """
        INSERT INTO notification
            (outbox_id, recipient_id, kind, post_id, comment_id, created_at, updated_at)
        VALUES (?, ?, 'POST_COMMENTED', 10, 100, ?, ?)
        """,
        outboxIds.getAndIncrement(),
        recipientId,
        BASE,
        BASE);

    return jdbc.queryForObject("SELECT MAX(id) FROM notification", Long.class);
  }

  private LocalDateTime readAtOf(long id) {
    return jdbc.queryForObject(
        "SELECT read_at FROM notification WHERE id = ?", LocalDateTime.class, id);
  }
}
