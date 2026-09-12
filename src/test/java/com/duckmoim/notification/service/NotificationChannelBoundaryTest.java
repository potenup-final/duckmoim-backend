package com.duckmoim.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.common.domain.NotificationKind;
import com.duckmoim.common.domain.NotificationOutbox;
import com.duckmoim.common.infra.NotificationOutboxRepository;
import com.duckmoim.notification.infra.NotificationPushSender;
import com.duckmoim.notification.infra.PermanentPushException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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
 * 한 채널이 실패해도 다른 채널의 결과가 남는가 (ADR 0010).
 *
 * <p>이 티켓의 완료 조건 첫 줄이다. <b>채널이 하나면 증명할 수 없어</b> 이음매에 실패하는 푸시를 꽂아 본다 — 그것이 {@code
 * NotificationPushSender} 를 지금 만든 이유다.
 *
 * <p><b>{@code @Transactional} 이 없다.</b> 단계가 트랜잭션 셋으로 갈린 것이 검증 대상이라, 검사가 트랜잭션을 쥐고 있으면 그 경계가 사라지고
 * <b>푸시 실패가 인앱을 롤백시켜도 초록불이 난다.</b>
 */
@SpringBootTest
class NotificationChannelBoundaryTest {

  private static final long RECIPIENT_ID = 98_001L;
  private static final long POST_ID = 10L;
  private static final long COMMENT_ID = 100L;

  /** 다음 푸시 호출이 무엇을 할지. 검사마다 갈아끼운다. */
  private static final AtomicReference<Runnable> PUSH = new AtomicReference<>(() -> {});

  @TestConfiguration
  static class FailingPushConfig {

    @Bean
    @Primary
    NotificationPushSender pushSender() {
      return delivery -> PUSH.get().run();
    }
  }

  @Autowired private NotificationDispatchBatch notificationDispatchBatch;
  @Autowired private NotificationOutboxRepository outboxRepository;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    PUSH.set(() -> {});
    clean();
  }

  @AfterEach
  void tearDown() {
    PUSH.set(() -> {});
    clean();
  }

  /**
   * 완료 조건 첫 줄이다 — <b>푸시가 죽어도 알림함은 남는다.</b>
   *
   * <p>한 트랜잭션에 담겨 있던 시절이면 이 단언이 깨진다. 인앱 INSERT 가 푸시 예외와 함께 롤백되어 알림함이 비고, 그때 {@code I-25} 가 도메인
   * 트랜잭션에서 끊어 낸 결합이 한 겹 안쪽에서 되살아난다.
   */
  @DisplayName("푸시가 실패해도 인앱 알림은 남는다.")
  @Test
  void inAppSurvivesPushFailure() {
    long outboxId = pendingOutbox();
    PUSH.set(
        () -> {
          throw new IllegalStateException("푸시가 죽었다");
        });

    notificationDispatchBatch.dispatchPendingNotifications();

    assertThat(notificationCount(outboxId)).isEqualTo(1);
  }

  /** 인앱이 남았어도 <b>행은 끝나지 않았다.</b> 푸시가 아직 못 갔으므로 다음 주기가 다시 집어야 한다. */
  @DisplayName("푸시가 실패하면 아웃박스는 보냈다고 적히지 않는다.")
  @Test
  void outboxStaysPendingOnPushFailure() {
    long outboxId = pendingOutbox();
    PUSH.set(
        () -> {
          throw new IllegalStateException("푸시가 죽었다");
        });

    notificationDispatchBatch.dispatchPendingNotifications();

    assertThat(statusOf(outboxId)).isEqualTo("PENDING");
    assertThat(attemptsOf(outboxId)).isEqualTo(1);
  }

  /**
   * <b>ADR 0010 이 적은 눈에 안 보이는 계약이다</b> — 「{@code PENDING} 인데 알림함에 행이 있다」가 「인앱 완료 · 푸시 대기」다.
   *
   * <p>재시도가 인앱을 다시 만들면 {@code uq_notification_outbox_id} 에 부딪힌다. 멱등 검사({@code existsByOutboxId})가
   * 그것을 막고, <b>그 검사를 지우면 여기가 빨간불이 된다.</b>
   */
  @DisplayName("푸시가 회복되면 인앱을 다시 만들지 않고 보냈다고 적는다.")
  @Test
  void retryAfterPushRecovers() {
    long outboxId = pendingOutbox();
    PUSH.set(
        () -> {
          throw new IllegalStateException("푸시가 죽었다");
        });
    notificationDispatchBatch.dispatchPendingNotifications();

    PUSH.set(() -> {});
    jdbc.update("UPDATE notification_outbox SET next_attempt_at = NULL WHERE id = ?", outboxId);
    notificationDispatchBatch.dispatchPendingNotifications();

    assertThat(notificationCount(outboxId)).isEqualTo(1);
    assertThat(statusOf(outboxId)).isEqualTo("SENT");
  }

  /**
   * 되돌릴 수 없는 실패는 <b>세 번을 쓰지 않는다</b> (ADR 0010 · NT-03).
   *
   * <p>잘못된 VAPID 설정이나 깨진 페이로드는 다시 보내도 같은 실패라, 3회를 소진하는 것은 주기 세 번을 버리는 일이다.
   */
  @DisplayName("되돌릴 수 없는 푸시 실패는 한 번에 DLQ 로 간다.")
  @Test
  void permanentPushFailureSkipsRetry() {
    long outboxId = pendingOutbox();
    PUSH.set(
        () -> {
          throw new PermanentPushException("VAPID 설정이 틀렸다");
        });

    notificationDispatchBatch.dispatchPendingNotifications();

    assertThat(statusOf(outboxId)).isEqualTo("없음");
    assertThat(dlqCount()).isEqualTo(1);
  }

  /** DLQ 로 간 것은 아웃박스 행이다. 사용자는 알림을 받았고 푸시만 못 갔다. */
  @DisplayName("DLQ 로 보내도 인앱 알림은 남는다.")
  @Test
  void inAppSurvivesPermanentPushFailure() {
    long outboxId = pendingOutbox();
    PUSH.set(
        () -> {
          throw new PermanentPushException("VAPID 설정이 틀렸다");
        });

    notificationDispatchBatch.dispatchPendingNotifications();

    assertThat(notificationCount(outboxId)).isEqualTo(1);
  }

  private int dlqCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM notification_outbox_dlq WHERE recipient_id = ?",
        Integer.class,
        RECIPIENT_ID);
  }

  private long pendingOutbox() {
    NotificationOutbox outbox =
        NotificationOutbox.of(NotificationKind.POST_COMMENTED, RECIPIENT_ID, POST_ID, COMMENT_ID);

    return outboxRepository.save(outbox).getId();
  }

  private int notificationCount(long outboxId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM notification WHERE outbox_id = ?", Integer.class, outboxId);
  }

  private String statusOf(long outboxId) {
    List<String> found =
        jdbc.queryForList(
            "SELECT status FROM notification_outbox WHERE id = ?", String.class, outboxId);

    return found.isEmpty() ? "없음" : found.get(0);
  }

  private int attemptsOf(long outboxId) {
    return jdbc.queryForObject(
        "SELECT attempts FROM notification_outbox WHERE id = ?", Integer.class, outboxId);
  }

  private void clean() {
    jdbc.update("DELETE FROM notification WHERE recipient_id = ?", RECIPIENT_ID);
    jdbc.update("DELETE FROM notification_outbox WHERE recipient_id = ?", RECIPIENT_ID);
    jdbc.update("DELETE FROM notification_outbox_dlq WHERE recipient_id = ?", RECIPIENT_ID);
  }
}
