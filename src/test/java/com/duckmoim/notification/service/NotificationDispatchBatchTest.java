package com.duckmoim.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
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
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * 워커가 실제로 도는지 (NT-02).
 *
 * <p><b>청크를 둘로 줄여 돌린다.</b> 반복이 한 번으로 끝나면 「보낼 것이 없어질 때까지」가 증명되지 않는다 — 마감 배치 테스트가 같은 방식으로 프로퍼티를 줄인다.
 *
 * <p>시계를 고정한다. 재시도 시각 비교가 걸려 있어 놓아두면 같은 데이터가 실행 시각에 따라 다르게 나온다.
 *
 * <p><b>{@code @Transactional} 을 붙이지 않는다.</b> 배치가 건마다 트랜잭션을 따로 열어서, 붙이면 그 경계가 사라진다. 두 표를 앞뒤로 비운다.
 */
@SpringBootTest(properties = "duckmoim.notification.worker.chunk=2")
class NotificationDispatchBatchTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 10, 0, 0);

  @TestConfiguration
  static class FixedClockConfig {

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneId.of("Asia/Seoul"));
    }
  }

  @Autowired private NotificationDispatchBatch notificationDispatchBatch;
  @Autowired private ScheduledTaskHolder scheduledTaskHolder;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM notification");
    jdbc.update("DELETE FROM notification_outbox");
  }

  @DisplayName("밀린 알림을 청크가 빌 때까지 모두 보낸다.")
  @Test
  void dispatchPendingNotifications() {
    // given — 청크가 둘이라 세 건이면 반복이 필요하다
    givenPendingOutbox(1L);
    givenPendingOutbox(2L);
    givenPendingOutbox(3L);

    // when
    notificationDispatchBatch.dispatchPendingNotifications();

    // then
    assertThat(count("notification")).isEqualTo(3);
    assertThat(count("notification_outbox WHERE status = 'SENT'")).isEqualTo(3);
  }

  @DisplayName("재시도 시각이 남은 건은 이번 주기에 보내지 않는다.")
  @Test
  void dispatchPendingNotifications_skipsWaiting() {
    // given
    givenPendingOutbox(1L);
    jdbc.update(
        "UPDATE notification_outbox SET attempts = 1, next_attempt_at = ?", NOW.plusHours(1));

    // when
    notificationDispatchBatch.dispatchPendingNotifications();

    // then
    assertThat(count("notification")).isZero();
  }

  /**
   * cron 프로퍼티가 비거나 {@code @EnableScheduling} 이 빠지면 나머지 검사는 전부 초록불인 채로 <b>아무 일도 일어나지 않는다.</b> 마감 배치
   * 테스트가 같은 이유로 같은 검사를 둔다.
   */
  @DisplayName("워커가 스케줄에 등록된다.")
  @Test
  void isScheduled() {
    // when
    List<String> tasks =
        scheduledTaskHolder.getScheduledTasks().stream()
            .map(ScheduledTask::toString)
            .filter(task -> task.contains("dispatchPendingNotifications"))
            .toList();

    // then
    assertThat(tasks).hasSize(1);
  }

  private void givenPendingOutbox(long commentId) {
    jdbc.update(
        """
        INSERT INTO notification_outbox
            (recipient_id, kind, post_id, comment_id, status, attempts, created_at, updated_at)
        VALUES (7, 'POST_COMMENTED', 10, ?, 'PENDING', 0, ?, ?)
        """,
        commentId,
        NOW,
        NOW);
  }

  private int count(String fromClause) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + fromClause, Integer.class);
  }
}
