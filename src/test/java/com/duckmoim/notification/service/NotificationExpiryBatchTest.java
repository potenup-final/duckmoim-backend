package com.duckmoim.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.TriggerTask;
import org.springframework.scheduling.support.SimpleTriggerContext;

/**
 * 만료 배치의 반복과 시각 기준, 그리고 주기가 실제로 등록되는지 (NT-11a).
 *
 * <p><b>청크를 2로 줄인다.</b> 기본값 500 으로는 반복이 도는지 보려면 501건을 넣어야 하고, 그러면 검사가 무엇을 보는지보다 데이터 만드는 코드가 길어진다
 * ({@code MeetTimePassedCloseBatchTest} 와 같은 판단이다).
 *
 * <p><b>시계를 고정한다.</b> 「30일이 지난 알림」과 「안 지난 알림」의 경계가 검증 대상이라 놓아두면 같은 데이터가 어제와 오늘 다르게 나온다. 시간대를 {@code
 * Asia/Seoul} 로 두는 것은 {@code ClockConfig} 와 같게 맞추려는 것이다 — <b>배치가 그 시계에서 UTC 를 뽑아내는지가 검증 대상이다.</b>
 *
 * <p><b>{@code @Transactional} 이 없다.</b> 배치가 청크마다 트랜잭션을 열고 닫으므로 검사가 트랜잭션을 쥐고 있으면 그 경계가 사라진다.
 */
@SpringBootTest(
    properties = {
      "duckmoim.notification.expiry.chunk=2",
      // 운영 기본값을 그대로 건다. build.gradle 이 스케줄을 꺼 두지만 (0 0 0 1 1 *) 그 값으로는
      // 「언제 도는가」를 볼 수 없다 — 인라인 프로퍼티가 시스템 프로퍼티를 이긴다
      "duckmoim.notification.expiry.cron=0 0 4 * * *"
    })
class NotificationExpiryBatchTest {

  /** 고정된 현재 시각. UTC 로 2026-09-14 00:00, 같은 순간의 KST 는 09:00 이다. */
  private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");

  /**
   * UTC 기준으로 30일을 <b>넘긴</b> 시각.
   *
   * <p>경계는 {@code 2026-08-15 00:00} 이고 조건이 {@code created_at < cutoff} 라, 경계에 정확히 걸친 값은 아직 만료가 아니다
   * — 하루 더 물린 값을 쓴다. 「30일이 지난」의 뜻이 그것이다.
   */
  private static final LocalDateTime EXPIRED = LocalDateTime.of(2026, 8, 14, 0, 0);

  /** UTC 경계(08-15 00:00)는 안 넘겼고 <b>KST 벽시계로 재면 옮겨진 경계(08-15 09:00)를 넘긴</b> 시각이다. */
  private static final LocalDateTime EXPIRED_ONLY_IN_KST = LocalDateTime.of(2026, 8, 15, 4, 0);

  private static final long ME = 97_001L;

  @TestConfiguration
  static class FixedClockConfig {

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
    }
  }

  @Autowired private NotificationExpiryBatch notificationExpiryBatch;
  @Autowired private ScheduledTaskHolder scheduledTaskHolder;
  @Autowired private JdbcTemplate jdbc;

  private final AtomicLong outboxIds = new AtomicLong(97_000);
  private final List<Long> inserted = new ArrayList<>();

  /** 롤백이 없으니 손으로 지운다. 넣은 행만 지워야 남의 검사가 안 깨진다. */
  @AfterEach
  void tearDown() {
    inserted.forEach(id -> jdbc.update("DELETE FROM notification WHERE id = ?", id));
    inserted.clear();
  }

  /** 청크가 2 인데 다섯 건이다 — 한 번 도는 것으로는 끝나지 않는다. */
  @DisplayName("만료된 알림이 청크보다 많아도 한 주기에 전부 지워진다.")
  @Test
  void deleteExpiredNotifications_drainsBeyondOneChunk() {
    for (int i = 0; i < 5; i++) {
      notified(EXPIRED);
    }

    notificationExpiryBatch.deleteExpiredNotifications();

    assertThat(survivors()).isEmpty();
  }

  /**
   * 시계가 KST 인데 {@code created_at} 은 UTC 로 저장된다는 사실이 이 배치에 직접 걸린다.
   *
   * <p>배치가 {@code LocalDateTime.now(clock)} 을 쓰면 KST 벽시계 09:00 이 들어가 경계가 아홉 시간 앞당겨지고, <b>아직 30일이 안
   * 된 알림이 매 실행마다 사라진다.</b> 고지한 보유 기간보다 일찍 지우는 것이라 늦게 지우는 것보다 고약하다.
   */
  @DisplayName("만료 판정은 UTC 로 한다 — KST 벽시계로는 일찍 지워질 알림이 남는다.")
  @Test
  void deleteExpiredNotifications_comparesInUtc() {
    long expired = notified(EXPIRED);
    long notExpired = notified(EXPIRED_ONLY_IN_KST);

    notificationExpiryBatch.deleteExpiredNotifications();

    assertThat(survivors()).containsExactly(notExpired);
    assertThat(exists(expired)).isFalse();
  }

  /** 읽음은 만료와 무관하다. 안 읽었어도 30일이 지나면 파기된다 (도메인-모델링.md 「6. 라이프사이클」). */
  @DisplayName("읽지 않은 알림도 30일이 지나면 지워진다.")
  @Test
  void deleteExpiredNotifications_isUnread() {
    long unread = notified(EXPIRED);

    notificationExpiryBatch.deleteExpiredNotifications();

    assertThat(exists(unread)).isFalse();
  }

  @DisplayName("지울 알림이 없어도 배치가 성공한다.")
  @Test
  void deleteExpiredNotifications_nothingIsExpired() {
    long fresh = notified(LocalDateTime.of(2026, 9, 13, 0, 0));

    notificationExpiryBatch.deleteExpiredNotifications();

    assertThat(exists(fresh)).isTrue();
  }

  /**
   * 주기가 실제로 등록되는지 본다.
   *
   * <p>배치 코드가 아무리 맞아도 cron 프로퍼티가 비면 <b>아무 일도 일어나지 않는데 검사는 전부 초록불이다.</b> 이 배치가 조용히 안 도는 것은 개인정보 처리방침
   * 제3조가 고지한 보유 기간이 깨지는 것이라 못박는다.
   */
  @DisplayName("만료 배치가 주기 작업으로 등록된다.")
  @Test
  void deleteExpiredNotifications_isScheduled() {
    List<String> tasks =
        scheduledTaskHolder.getScheduledTasks().stream()
            .map(ScheduledTask::toString)
            .filter(task -> task.contains("deleteExpiredNotifications"))
            .toList();

    assertThat(tasks).hasSize(1);
  }

  /**
   * <b>등록만으로는 모자란다.</b> 위 검사는 주기 작업이 잡혔는지만 보고, {@code zone} 을 빼도 통과한다 — 그때 이 배치는 한국 시각 <b>오후
   * 1시</b>에 돈다. JVM 기본 시간대를 쓰는데 {@code Dockerfile} 의 {@code eclipse-temurin} 에 {@code TZ} 가 없어
   * 컨테이너에서 UTC 이기 때문이다.
   *
   * <p>그래서 트리거에게 <b>다음 실행이 언제냐</b>고 직접 묻는다. 9/14 09:00 KST 에서 다음 새벽 4시는 9/15 04:00 KST 이고, 그 순간은
   * UTC 로 9/14 19:00 이다. {@code zone} 이 빠지면 9/15 04:00 UTC 가 나와 다섯 시간 어긋난다.
   */
  @DisplayName("만료 배치는 한국 시각 새벽 4시에 돈다.")
  @Test
  void deleteExpiredNotifications_runsAtFourInKst() {
    Trigger trigger = triggerOf("deleteExpiredNotifications");
    SimpleTriggerContext context = new SimpleTriggerContext(Clock.systemUTC());
    context.update(null, null, Instant.parse("2026-09-14T00:00:00Z"));

    assertThat(trigger.nextExecution(context)).isEqualTo(Instant.parse("2026-09-14T19:00:00Z"));
  }

  private Trigger triggerOf(String methodName) {
    return scheduledTaskHolder.getScheduledTasks().stream()
        .filter(task -> task.toString().contains(methodName))
        .map(ScheduledTask::getTask)
        .filter(TriggerTask.class::isInstance)
        .map(task -> ((TriggerTask) task).getTrigger())
        .findFirst()
        .orElseThrow();
  }

  private long notified(LocalDateTime createdAt) {
    jdbc.update(
        """
        INSERT INTO notification
            (outbox_id, recipient_id, kind, post_id, comment_id, created_at, updated_at)
        VALUES (?, ?, 'POST_COMMENTED', 10, 100, ?, ?)
        """,
        outboxIds.getAndIncrement(),
        ME,
        createdAt,
        createdAt);

    long id = jdbc.queryForObject("SELECT MAX(id) FROM notification", Long.class);
    inserted.add(id);

    return id;
  }

  private List<Long> survivors() {
    return jdbc.queryForList(
        "SELECT id FROM notification WHERE recipient_id = ? ORDER BY id", Long.class, ME);
  }

  private boolean exists(long id) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE id = ?", Integer.class, id)
        == 1;
  }
}
