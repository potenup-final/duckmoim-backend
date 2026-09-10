package com.duckmoim.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 발송 한 건의 규칙 (NT-02 · NT-03).
 *
 * <p>실제 MySQL 로 돈다. 「집힌다 · 안 집힌다」가 조회 조건과 시각 비교로 갈려 mock 으로는 검증되지 않는다.
 *
 * <p><b>클래스에 {@code @Transactional} 을 붙이지 않는다.</b> 발송과 실패 기록이 서로 다른 트랜잭션이라 (실패 기록이 롤백에 함께 지워지면 안
 * 된다) 붙이면 그 경계가 사라진다. 대신 두 표를 앞뒤로 비운다 — 아웃박스와 알림함은 기술 기록이라 다른 테스트가 남긴 행을 남겨 둘 이유가 없고, 워커는 표에 있는 것을
 * 전부 집으므로 남아 있으면 이 검사가 그것까지 센다.
 */
@SpringBootTest
class NotificationDispatchServiceTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 10, 0, 0);
  private static final long RECIPIENT_ID = 7L;
  private static final long POST_ID = 10L;
  private static final long COMMENT_ID = 100L;

  @Autowired private NotificationDispatchService notificationDispatchService;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM notification");
    jdbc.update("DELETE FROM notification_outbox_dlq");
    jdbc.update("DELETE FROM notification_outbox");
  }

  @DisplayName("아웃박스에 쌓인 건을 보내면 알림함에 알림이 생긴다.")
  @Test
  void dispatch() {
    // given
    long outboxId = givenPendingOutbox();

    // when
    boolean sent = notificationDispatchService.dispatch(outboxId);

    // then — 알림에 필요한 값이 아웃박스에서 그대로 옮겨진다
    assertThat(sent).isTrue();
    assertThat(notifications())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row).containsEntry("outbox_id", outboxId);
              assertThat(row).containsEntry("recipient_id", RECIPIENT_ID);
              assertThat(row).containsEntry("kind", "POST_COMMENTED");
              assertThat(row).containsEntry("post_id", POST_ID);
              assertThat(row).containsEntry("comment_id", COMMENT_ID);
              assertThat(row.get("read_at")).isNull();
            });
  }

  @DisplayName("보낸 아웃박스 행은 다시 집히지 않는다.")
  @Test
  void findSendableIds_excludesSent() {
    // given
    long outboxId = givenPendingOutbox();

    // when
    notificationDispatchService.dispatch(outboxId);

    // then
    assertThat(notificationDispatchService.findSendableIds(NOW, 10)).isEmpty();
  }

  @DisplayName("같은 아웃박스 행으로 알림이 두 번 만들어지지 않는다.")
  @Test
  void dispatch_isIdempotent() {
    // given — 알림을 만든 뒤 상태를 바꾸기 전에 워커가 죽은 상태를 만든다
    long outboxId = givenPendingOutbox();
    notificationDispatchService.dispatch(outboxId);
    jdbc.update("UPDATE notification_outbox SET status = 'PENDING' WHERE id = ?", outboxId);

    // when
    boolean sent = notificationDispatchService.dispatch(outboxId);

    // then — 새로 만들지 않고 보냈다고만 적는다
    assertThat(sent).isFalse();
    assertThat(notifications()).hasSize(1);
    assertThat(outboxColumn(outboxId, "status")).isEqualTo("SENT");
  }

  @DisplayName("발송이 실패하면 다음 시도가 백오프만큼 밀린다.")
  @Test
  void recordFailure() {
    // given
    long outboxId = givenPendingOutbox();

    // when
    boolean exhausted = notificationDispatchService.recordFailure(outboxId, NOW);

    // then — 첫 간격이 1분이다
    assertThat(exhausted).isFalse();
    assertThat(outboxColumn(outboxId, "attempts")).isEqualTo(1);
    assertThat(nextAttemptAt(outboxId)).isEqualTo(NOW.plusMinutes(1));
  }

  @DisplayName("재시도 시각이 되기 전에는 다시 집지 않는다.")
  @Test
  void findSendableIds_waitsForNextAttempt() {
    // given
    long outboxId = givenPendingOutbox();

    // when
    notificationDispatchService.recordFailure(outboxId, NOW);

    // then
    assertThat(notificationDispatchService.findSendableIds(NOW, 10)).isEmpty();
    assertThat(notificationDispatchService.findSendableIds(NOW.plusMinutes(1), 10))
        .containsExactly(outboxId);
  }

  @DisplayName("실패가 거듭되면 재시도 간격이 늘어난다.")
  @Test
  void recordFailure_backoffGrows() {
    // given
    long outboxId = givenPendingOutbox();

    // when
    notificationDispatchService.recordFailure(outboxId, NOW);
    notificationDispatchService.recordFailure(outboxId, NOW);

    // then — 1분 다음이 5분이다
    assertThat(outboxColumn(outboxId, "attempts")).isEqualTo(2);
    assertThat(nextAttemptAt(outboxId)).isEqualTo(NOW.plusMinutes(5));
  }

  @DisplayName("세 번 실패한 건은 DLQ 로 옮겨지고 아웃박스에서 사라진다.")
  @Test
  void recordFailure_movesToDlq() {
    // given
    long outboxId = givenPendingOutbox();
    notificationDispatchService.recordFailure(outboxId, NOW);
    notificationDispatchService.recordFailure(outboxId, NOW);

    // when
    boolean movedToDlq = notificationDispatchService.recordFailure(outboxId, NOW);

    // then — 원본을 지우므로 보낼 것이 무엇이었는지는 DLQ 행에만 남는다
    assertThat(movedToDlq).isTrue();
    assertThat(jdbc.queryForList("SELECT * FROM notification_outbox WHERE id = ?", outboxId))
        .isEmpty();
    assertThat(dlqRows())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row).containsEntry("outbox_id", outboxId);
              assertThat(row).containsEntry("recipient_id", RECIPIENT_ID);
              assertThat(row).containsEntry("kind", "POST_COMMENTED");
              assertThat(row).containsEntry("post_id", POST_ID);
              assertThat(row).containsEntry("comment_id", COMMENT_ID);
              assertThat(row).containsEntry("attempts", 3);
              assertThat(row).containsEntry("failed_at", NOW);
            });
  }

  @DisplayName("DLQ 로 옮긴 건은 더 집히지 않는다.")
  @Test
  void findSendableIds_excludesDlq() {
    // given
    long outboxId = givenPendingOutbox();
    notificationDispatchService.recordFailure(outboxId, NOW);
    notificationDispatchService.recordFailure(outboxId, NOW);

    // when
    notificationDispatchService.recordFailure(outboxId, NOW);

    // then — 아무리 기다려도 다시 집히지 않는다
    assertThat(notificationDispatchService.findSendableIds(NOW.plusYears(1), 10)).isEmpty();
  }

  private long givenPendingOutbox() {
    jdbc.update(
        """
        INSERT INTO notification_outbox
            (recipient_id, kind, post_id, comment_id, status, attempts, created_at, updated_at)
        VALUES (?, 'POST_COMMENTED', ?, ?, 'PENDING', 0, ?, ?)
        """,
        RECIPIENT_ID,
        POST_ID,
        COMMENT_ID,
        NOW,
        NOW);

    return jdbc.queryForObject("SELECT MAX(id) FROM notification_outbox", Long.class);
  }

  private List<Map<String, Object>> notifications() {
    return jdbc.queryForList(
        "SELECT outbox_id, recipient_id, kind, post_id, comment_id, read_at FROM notification");
  }

  private List<Map<String, Object>> dlqRows() {
    return jdbc.queryForList(
        """
        SELECT outbox_id, recipient_id, kind, post_id, comment_id, attempts, failed_at
        FROM notification_outbox_dlq
        """);
  }

  private Object outboxColumn(long outboxId, String column) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM notification_outbox WHERE id = ?", Object.class, outboxId);
  }

  private LocalDateTime nextAttemptAt(long outboxId) {
    return jdbc.queryForObject(
        "SELECT next_attempt_at FROM notification_outbox WHERE id = ?",
        LocalDateTime.class,
        outboxId);
  }
}
