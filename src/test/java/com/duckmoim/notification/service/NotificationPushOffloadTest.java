package com.duckmoim.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.common.domain.NotificationKind;
import com.duckmoim.common.domain.NotificationOutbox;
import com.duckmoim.common.domain.NotificationTarget;
import com.duckmoim.common.infra.NotificationOutboxRepository;
import com.duckmoim.notification.infra.NotificationPushSender;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 푸시가 느려도 알림함은 늦지 않는다 (NT-02 · NT-13 · STAR-149 · QA-FAIL-04).
 *
 * <p><b>이 검사가 QA-FAIL-04 다.</b> 한 줄로 처리하던 때는 느린 푸시 뒤에 선 사람의 알림함이 그 푸시를 기다렸다 (실측: 느린 푸시 5건 뒤 49초).
 *
 * <pre>
 * 전  주기가 끝나기 전에 알림함 1건 → 푸시가 안 끝나 주기도 안 끝남
 * 후  주기가 끝나면 알림함 3건 전부 → 푸시는 일꾼이 계속
 * </pre>
 *
 * <p><b>푸시가 끝나지 않게 붙들어 둔다.</b> 시간으로 느리게 만들면 기계 속도에 결과를 맡기게 된다 — 래치를 풀기 전까지 푸시는 영원히 진행 중이다.
 *
 * <p><b>일꾼을 하나로, 넘기기 마감을 1초로, 청크를 둘로 줄인다.</b> 자리가 끝내 안 날 때 넘기지 못한 건이 어떻게 되는지를 15초 기다리지 않고, 적체가 여러
 * 청크일 때까지 보려는 것이다.
 *
 * <p><b>{@code @Transactional} 을 붙이지 않는다.</b> 워커가 건마다 트랜잭션을 따로 열고 일꾼은 다른 스레드다.
 */
@SpringBootTest(
    properties = {
      "duckmoim.notification.worker.push-pool-size=1",
      "duckmoim.notification.worker.handoff-wait=1s",
      "duckmoim.notification.worker.chunk=2"
    })
@DisplayName("푸시 일꾼으로 넘기기")
class NotificationPushOffloadTest {

  private static final long RECIPIENT_ID = 98_101L;

  /** 푸시가 붙들려 있는 동안 닫혀 있다. 검사마다 새로 만든다. */
  private static volatile CountDownLatch pushGate = new CountDownLatch(0);

  private static final AtomicInteger PUSH_CALLS = new AtomicInteger();

  /** 문이 열린 뒤 푸시 한 건이 걸리는 시간. 0 이면 바로 끝난다. 푸시 서비스가 정상인 날을 흉내 낼 때 쓴다. */
  private static volatile long pushMillis = 0;

  @TestConfiguration
  static class BlockingPushConfig {

    @Bean
    @Primary
    NotificationPushSender pushSender() {
      return delivery -> {
        PUSH_CALLS.incrementAndGet();
        try {
          pushGate.await(10, TimeUnit.SECONDS);
          TimeUnit.MILLISECONDS.sleep(pushMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      };
    }
  }

  @Autowired private NotificationDispatchBatch notificationDispatchBatch;
  @Autowired private NotificationOutboxRepository outboxRepository;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    clean();
    PUSH_CALLS.set(0);
    pushMillis = 0;
    pushGate = new CountDownLatch(1);
  }

  /** 붙들린 푸시를 풀어 두지 않으면 일꾼이 물린 채 다음 검사와 컨텍스트 종료가 기다린다. */
  @AfterEach
  void tearDown() {
    pushGate.countDown();
    Awaitility.await().atMost(15, TimeUnit.SECONDS).until(this::noOutboxInFlight);
    clean();
  }

  /** <b>이 검사가 QA-FAIL-04 의 기준이다</b> — 느린 푸시 뒤에 선 알림도 그 주기 안에 알림함에 들어간다. */
  @DisplayName("푸시가 끝나지 않아도 한 주기가 끝나면 모든 알림이 알림함에 있다.")
  @Test
  void dispatch_deliversEveryInboxWithoutWaitingForPush() {
    List<Long> outboxIds = List.of(pendingOutbox(1L), pendingOutbox(2L), pendingOutbox(3L));

    notificationDispatchBatch.dispatchPendingNotifications();

    assertThat(outboxIds).allSatisfy(id -> assertThat(notificationCount(id)).isEqualTo(1));
  }

  /**
   * <b>넘기기 마감까지 자리가 안 나면, 못 넘긴 건은 리스를 둔 채 둔다.</b>
   *
   * <p>무한정 기다리면 넘긴 뒤 발송까지 리스를 넘겨 다른 인스턴스가 같은 건을 집는다 (NT-04). 리스를 바로 풀면 같은 주기의 다음 청크가 다시 집어 드레인이
   * 헛돈다. 그래서 푸시가 붙들린 동안 마감(1초)이 지나면 셋 다 리스가 걸린 채 {@code PENDING} 이고, 푸시는 한 건만 시작됐다.
   */
  @DisplayName("넘기기 마감까지 일꾼 자리가 안 나면 넘기지 못한 건은 리스를 둔 채 미룬다.")
  @Test
  void dispatch_keepsLeaseForPushesItCouldNotHandOff() {
    List<Long> outboxIds = List.of(pendingOutbox(1L), pendingOutbox(2L), pendingOutbox(3L));

    notificationDispatchBatch.dispatchPendingNotifications();

    Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> PUSH_CALLS.get() == 1);
    assertThat(outboxIds)
        .allSatisfy(
            id -> {
              assertThat(statusOf(id)).isEqualTo("PENDING");
              assertThat(attemptsOf(id)).isZero();
              assertThat(isLeased(id)).isTrue();
            });
  }

  /**
   * 리스가 풀리면 <b>알림함은 건너뛰고 푸시만</b> 다시 넘긴다 — ADR 0010 의 「{@code PENDING} 인데 알림함에 행이 있다」 계약이다.
   *
   * <p>먼저 푸시를 붙들어 두 건을 미루게 만들고, 푸시를 풀고 리스를 손으로 푼 뒤 한 주기를 더 돌린다.
   */
  @DisplayName("리스가 풀리면 미뤘던 건은 알림함을 다시 만들지 않고 푸시만 보낸다.")
  @Test
  void dispatch_pushesDeferredOnesAfterLeaseExpires() {
    List<Long> outboxIds = List.of(pendingOutbox(1L), pendingOutbox(2L), pendingOutbox(3L));
    notificationDispatchBatch.dispatchPendingNotifications();
    assertThat(PUSH_CALLS.get()).isEqualTo(1);

    pushGate.countDown();
    Awaitility.await().atMost(5, TimeUnit.SECONDS).until(this::noOutboxInFlight);
    jdbc.update(
        "UPDATE notification_outbox SET next_attempt_at = NULL"
            + " WHERE recipient_id = ? AND status = 'PENDING'",
        RECIPIENT_ID);
    notificationDispatchBatch.dispatchPendingNotifications();

    Awaitility.await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> outboxIds.stream().allMatch(id -> "SENT".equals(statusOf(id))));
    assertThat(outboxIds).allSatisfy(id -> assertThat(notificationCount(id)).isEqualTo(1));
    assertThat(PUSH_CALLS.get()).isEqualTo(3);
  }

  /**
   * <b>푸시 서비스가 정상인 날에는 한 주기에 들어온 알림을 미루지 않는다</b> (STAR-149 리뷰).
   *
   * <p>채팅방에 메시지 하나가 오면 멤버 수만큼 알림이 한꺼번에 생긴다. 일꾼이 바쁘다고 곧바로 미루면, 미룬 건은 리스(30초)가 풀릴 때까지 아무도 못 집어
   * <b>평소에도 푸시가 30~40초 늦는다</b> — 한 줄로 처리하던 때보다 느려진다.
   *
   * <pre>
   * 일꾼 1 · 푸시 50ms · 알림 10건
   * 곧바로 미루면   1건 나가고 9건이 리스 30초에 묶임
   * 자리를 기다리면  10건이 0.5초 안에 나감
   * </pre>
   */
  @DisplayName("푸시 서비스가 정상이면 한 주기에 들어온 알림을 미루지 않고 모두 보낸다.")
  @Test
  void dispatch_sendsBurstWithoutDeferringOnNormalDay() {
    pushMillis = 50;
    pushGate.countDown();
    List<Long> outboxIds = new java.util.ArrayList<>();
    for (long commentId = 1; commentId <= 10; commentId++) {
      outboxIds.add(pendingOutbox(commentId));
    }

    notificationDispatchBatch.dispatchPendingNotifications();

    Awaitility.await()
        .atMost(3, TimeUnit.SECONDS)
        .until(() -> outboxIds.stream().allMatch(id -> "SENT".equals(statusOf(id))));
  }

  /**
   * <b>느린 날 적체가 여러 청크여도 알림함은 청크마다 기다리지 않는다.</b>
   *
   * <p>한 청크가 넘기기 마감에 걸렸다면 일꾼이 꽉 찬 것이다. 그 뒤 청크까지 마감만큼 기다리면 적체 1,000건(청크 10개)에서 뒤 청크의 알림함이 150초 늦는다 —
   * 이 티켓이 없애려던 것과 같은 모양이다.
   *
   * <pre>
   * 알림 6건 · 청크 2 · 마감 1초 · 푸시 붙들림
   * 청크마다 기다리면   1초 + 1초 + 1초 = 3초
   * 한 번만 기다리면    1초
   * </pre>
   */
  @DisplayName("느린 날 한 청크가 마감에 걸리면 그 주기의 나머지 청크는 기다리지 않고 알림함만 만든다.")
  @Test
  void dispatch_stopsWaitingOnceWorkersAreSaturated() {
    List<Long> outboxIds = new java.util.ArrayList<>();
    for (long commentId = 1; commentId <= 6; commentId++) {
      outboxIds.add(pendingOutbox(commentId));
    }
    long startedAt = System.nanoTime();

    notificationDispatchBatch.dispatchPendingNotifications();

    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    assertThat(outboxIds).allSatisfy(id -> assertThat(notificationCount(id)).isEqualTo(1));
    assertThat(elapsedMillis).isLessThan(2_000);
  }

  /** 일꾼이 쥔 건이 없는가 — 시작된 푸시 수만큼 결과가 적혔는가로 본다. */
  private boolean noOutboxInFlight() {
    Integer finished =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM notification_outbox"
                + " WHERE recipient_id = ? AND (status = 'SENT' OR attempts > 0)",
            Integer.class,
            RECIPIENT_ID);
    return finished >= PUSH_CALLS.get();
  }

  private long pendingOutbox(long commentId) {
    NotificationOutbox outbox =
        NotificationOutbox.of(
            NotificationKind.POST_COMMENTED,
            RECIPIENT_ID,
            NotificationTarget.ofComment(10L, commentId));

    return outboxRepository.save(outbox).getId();
  }

  private int notificationCount(long outboxId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM notification WHERE outbox_id = ?", Integer.class, outboxId);
  }

  private String statusOf(long outboxId) {
    return jdbc.queryForObject(
        "SELECT status FROM notification_outbox WHERE id = ?", String.class, outboxId);
  }

  private int attemptsOf(long outboxId) {
    return jdbc.queryForObject(
        "SELECT attempts FROM notification_outbox WHERE id = ?", Integer.class, outboxId);
  }

  /**
   * 리스가 걸려 있는가.
   *
   * <p>발행된 행은 {@code next_attempt_at} 이 비어 있고 선점이 그 칸에 리스를 적는다 (ADR 0008). DB 시각과 견주지 않는 것은 JVM 과
   * DB 의 시간대 설정에 결과가 흔들리지 않게 하려는 것이다.
   */
  private boolean isLeased(long outboxId) {
    return jdbc.queryForObject(
        "SELECT next_attempt_at IS NOT NULL FROM notification_outbox WHERE id = ?",
        Boolean.class,
        outboxId);
  }

  private void clean() {
    jdbc.update("DELETE FROM notification WHERE recipient_id = ?", RECIPIENT_ID);
    jdbc.update("DELETE FROM notification_outbox WHERE recipient_id = ?", RECIPIENT_ID);
    jdbc.update("DELETE FROM notification_outbox_dlq WHERE recipient_id = ?", RECIPIENT_ID);
  }
}
