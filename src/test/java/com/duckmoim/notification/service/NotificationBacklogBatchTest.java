package com.duckmoim.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * 밀린 양이 로그로 나오는지 (NT-05).
 *
 * <p>검증 기준이 <i>「로그에서 대기 건수를 읽을 수 있다」</i> 라서 <b>로그를 직접 잡아 읽는다.</b> 서비스가 센 값만 확인하면 그 값이 실제로 어디에도 안
 * 나가도 초록불이다.
 *
 * <p><b>{@code @Transactional} 을 붙이지 않는다.</b> 배치가 자기 트랜잭션을 여는 구조라 붙이면 경계가 사라진다. 두 표를 앞뒤로 비운다.
 */
@SpringBootTest
class NotificationBacklogBatchTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 12, 0, 0);

  private static final long RECIPIENT_ID = 93_001L;

  private final AtomicLong outboxIds = new AtomicLong(93_000);

  @Autowired private NotificationBacklogBatch notificationBacklogBatch;
  @Autowired private ScheduledTaskHolder scheduledTaskHolder;
  @Autowired private JdbcTemplate jdbc;

  private Logger batchLogger;
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM notification");
    jdbc.update("DELETE FROM notification_outbox_dlq");
    jdbc.update("DELETE FROM notification_outbox");
  }

  @BeforeEach
  void captureLogs() {
    logs = new ListAppender<>();
    logs.start();
    batchLogger = (Logger) LoggerFactory.getLogger(NotificationBacklogBatch.class);
    batchLogger.addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    batchLogger.detachAppender(logs);
  }

  @DisplayName("대기 건수와 DLQ 건수를 한 줄에 남긴다.")
  @Test
  void logBacklog() {
    givenPendingOutbox(3);
    givenDeadLettered(2);

    notificationBacklogBatch.logBacklog();

    assertThat(onlyMessage()).contains("pending=3").contains("deadLettered=2");
  }

  /**
   * 0 건일 때도 남기는 것이 이 요구사항의 핵심이다.
   *
   * <p>빼면 로그가 없는 것이 「평온하다」인지 「배치가 죽었다」인지 구분되지 않는다. 발송 쪽이 「0건 보냄」을 빼는 것과 반대 판단이고, 그쪽은 10초 주기라 줄이 쌓이는
   * 것이 문제였다.
   */
  @DisplayName("밀린 것이 없어도 로그를 남긴다.")
  @Test
  void logBacklog_isEmpty() {
    notificationBacklogBatch.logBacklog();

    assertThat(onlyMessage()).contains("pending=0").contains("deadLettered=0");
  }

  /** 선점된 건도 아직 알림함에 안 들어갔으므로 적체다. */
  @DisplayName("선점된 건도 대기 건수에 들어간다.")
  @Test
  void logBacklog_countsClaimed() {
    givenPendingOutbox(3);

    notificationBacklogBatch.logBacklog();

    assertThat(onlyMessage()).contains("pending=3");
  }

  @DisplayName("적체 관측이 스케줄에 등록되어 있다.")
  @Test
  void logBacklog_isScheduled() {
    assertThat(scheduledTaskHolder.getScheduledTasks())
        .extracting(ScheduledTask::toString)
        .anyMatch(task -> task.contains("NotificationBacklogBatch.logBacklog"));
  }

  private String onlyMessage() {
    List<ILoggingEvent> events = logs.list;
    assertThat(events).as("적체 로그가 한 줄 나와야 한다").hasSize(1);
    return events.get(0).getFormattedMessage();
  }

  private void givenPendingOutbox(int rows) {
    for (int i = 0; i < rows; i++) {
      jdbc.update(
          """
          INSERT INTO notification_outbox
              (recipient_id, kind, post_id, comment_id, status, attempts, created_at, updated_at)
          VALUES (?, 'POST_COMMENTED', 10, ?, 'PENDING', 0, ?, ?)
          """,
          RECIPIENT_ID,
          outboxIds.getAndIncrement(),
          NOW,
          NOW);
    }
  }

  private void givenDeadLettered(int rows) {
    for (int i = 0; i < rows; i++) {
      jdbc.update(
          """
          INSERT INTO notification_outbox_dlq
              (outbox_id, recipient_id, kind, post_id, comment_id, attempts, failed_at,
               created_at, updated_at)
          VALUES (?, ?, 'POST_COMMENTED', 10, 100, 3, ?, ?, ?)
          """,
          outboxIds.getAndIncrement(),
          RECIPIENT_ID,
          NOW,
          NOW,
          NOW);
    }
  }
}
