package com.duckmoim.notification.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만료 대상을 집고 지우는 질의 (NT-11a).
 *
 * <p>실제 MySQL 로 돈다. 삭제가 벌크라 mock 으로는 몇 행이 지워졌는지 볼 수 없고, 테스트 컨벤션이 H2 도 금지했다.
 *
 * <p><b>기준이 생성 시각이라는 것이 이 검사의 요지다.</b> 읽음 여부는 아무 영향이 없다 (도메인-모델링.md 「6. 상태 전이」).
 */
@SpringBootTest
@Transactional
class NotificationExpiryRepositoryTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 14, 0, 0);

  /** 30일 경계. 이보다 앞서 만들어진 알림이 만료다. */
  private static final LocalDateTime CUTOFF = NOW.minusDays(30);

  private static final long ME = 96_001L;

  private final AtomicLong outboxIds = new AtomicLong(96_000);

  @Autowired private NotificationRepository notificationRepository;
  @Autowired private JdbcTemplate jdbc;

  @DisplayName("30일이 지난 알림이 만료 대상으로 집힌다.")
  @Test
  void findExpiredIds() {
    long expired = notified(CUTOFF.minusSeconds(1));
    notified(CUTOFF.plusSeconds(1));

    assertThat(expiredIds(10)).containsExactly(expired);
  }

  /** 경계다. 「30일이 지난」이므로 정확히 경계에 걸친 알림은 아직 남는다. */
  @DisplayName("경계에 정확히 걸친 알림은 아직 만료가 아니다.")
  @Test
  void findExpiredIds_isExactlyOnCutoff() {
    notified(CUTOFF);

    assertThat(expiredIds(10)).isEmpty();
  }

  /** 만료 기준이 읽음이 아니라는 것을 보는 자리다. 안 읽었어도 30일이 지나면 파기된다. */
  @DisplayName("읽지 않은 알림도 30일이 지나면 만료 대상이다.")
  @Test
  void findExpiredIds_isUnread() {
    long unread = notified(CUTOFF.minusDays(1));

    assertThat(readAtOf(unread)).isNull();
    assertThat(expiredIds(10)).containsExactly(unread);
  }

  /** 읽었다고 일찍 지우지 않는다. 읽음은 만료와 무관하다. */
  @DisplayName("읽은 알림도 30일 전이면 만료 대상이 아니다.")
  @Test
  void findExpiredIds_isReadButFresh() {
    long read = notified(NOW.minusDays(1));
    jdbc.update("UPDATE notification SET read_at = ? WHERE id = ?", NOW, read);

    assertThat(expiredIds(10)).isEmpty();
  }

  /**
   * <b>오래된 것부터다.</b> 두 인스턴스가 같은 순서로 읽어야 락 획득 순서가 같아져 데드락이 나지 않는다 — 이 배치는 중복 실행을 잠금으로 막지 않는다 (ADR
   * 0009).
   */
  @DisplayName("만료 대상은 오래된 것부터 청크만큼 집힌다.")
  @Test
  void findExpiredIds_isChunked() {
    long oldest = notified(CUTOFF.minusDays(3));
    long middle = notified(CUTOFF.minusDays(2));
    notified(CUTOFF.minusDays(1));

    assertThat(expiredIds(2)).containsExactly(oldest, middle);
  }

  @DisplayName("집어 둔 번호만 지워진다.")
  @Test
  void deleteAllByIdIn() {
    long expired = notified(CUTOFF.minusDays(1));
    long fresh = notified(NOW);

    int deleted = notificationRepository.deleteAllByIdIn(List.of(expired));

    assertThat(deleted).isEqualTo(1);
    assertThat(exists(expired)).isFalse();
    assertThat(exists(fresh)).isTrue();
  }

  /** 남이 먼저 지웠을 때의 모양이다. 배치 둘이 같이 돌면 실제로 이렇게 된다. */
  @DisplayName("이미 지워진 번호가 섞여 있어도 나머지는 지워진다.")
  @Test
  void deleteAllByIdIn_someAreAlreadyGone() {
    long expired = notified(CUTOFF.minusDays(1));
    long gone = notified(CUTOFF.minusDays(2));
    jdbc.update("DELETE FROM notification WHERE id = ?", gone);

    int deleted = notificationRepository.deleteAllByIdIn(List.of(gone, expired));

    assertThat(deleted).isEqualTo(1);
    assertThat(exists(expired)).isFalse();
  }

  private List<Long> expiredIds(int chunk) {
    return notificationRepository.findExpiredIds(CUTOFF, PageRequest.ofSize(chunk));
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

    return jdbc.queryForObject("SELECT MAX(id) FROM notification", Long.class);
  }

  private boolean exists(long id) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE id = ?", Integer.class, id)
        == 1;
  }

  private LocalDateTime readAtOf(long id) {
    return jdbc.queryForObject(
        "SELECT read_at FROM notification WHERE id = ?", LocalDateTime.class, id);
  }
}
