package com.duckmoim.notification.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.notification.domain.Notification;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 읽음 처리와 안 읽은 수의 질의 (NT-09 · NT-10).
 *
 * <p>실제 MySQL 로 돈다. 벌크 UPDATE 가 영속성 컨텍스트를 지나지 않아 mock 으로는 「몇 행이 바뀌었나」도 「{@code updated_at} 이 채워졌나」도
 * 볼 수 없고, 테스트 컨벤션이 H2 도 금지했다.
 *
 * <p><b>여기가 {@code I-24} 를 쓰기 쪽에서 보는 자리다.</b> 지금까지 그 불변식은 조회 질의 하나가 지켰는데 (도메인-모델링.md 「5. 불변식」 · 이중
 * 방어가 없다) 이 티켓이 알림에 쓰기 경로를 처음 연다. 세 질의 모두 남의 행에 닿지 않는 것을 본다.
 */
@SpringBootTest
@Transactional
class NotificationReadRepositoryTest {

  private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 14, 0, 0);
  private static final LocalDateTime READ_AT = BASE.plusHours(5);

  /** 다른 검사가 쓰지 않는 번호를 쓴다 — 「내 것이 정확히 이것뿐」을 단언하므로 남의 행이 섞이면 깨진다. */
  private static final long ME = 93_001L;

  private static final long SOMEONE_ELSE = 93_002L;

  private final AtomicLong outboxIds = new AtomicLong(93_000);

  @Autowired private NotificationRepository notificationRepository;
  @Autowired private JdbcTemplate jdbc;

  @DisplayName("내 알림은 번호와 수신자로 찾힌다.")
  @Test
  void findByIdAndRecipientId() {
    long mine = notified(ME);

    Optional<Notification> found = notificationRepository.findByIdAndRecipientId(mine, ME);

    assertThat(found).isPresent();
    assertThat(found.get().getId()).isEqualTo(mine);
  }

  /**
   * 남의 알림이 <b>없는 것과 똑같이</b> 비어서 돌아온다. 호출부가 이 빈 값을 404 로 옮기고, 그래서 「그 번호의 알림이 존재한다」가 새지 않는다
   * (API-설계.md 「5. 결정 사항」 D-14 ②).
   */
  @DisplayName("남의 알림은 번호를 알아도 찾히지 않는다.")
  @Test
  void findByIdAndRecipientId_recipientIsNotMe() {
    long theirs = notified(SOMEONE_ELSE);

    assertThat(notificationRepository.findByIdAndRecipientId(theirs, ME)).isEmpty();
  }

  @DisplayName("전체 읽음은 내 안 읽은 알림만 읽음으로 바꾼다.")
  @Test
  void markAllRead() {
    long mine = notified(ME);
    long another = notified(ME);
    long theirs = notified(SOMEONE_ELSE);

    int changed = notificationRepository.markAllRead(ME, READ_AT);

    assertThat(changed).isEqualTo(2);
    assertThat(readAtOf(mine)).isEqualTo(READ_AT);
    assertThat(readAtOf(another)).isEqualTo(READ_AT);
    assertThat(readAtOf(theirs)).isNull();
  }

  /** 도메인의 「덮어쓰지 않는다」와 같은 규칙을 {@code read_at is null} 조건이 SQL 쪽에서 지킨다. */
  @DisplayName("전체 읽음은 이미 읽은 알림의 시각을 건드리지 않는다.")
  @Test
  void markAllRead_keepsFirstStamp() {
    long already = notified(ME);
    LocalDateTime first = BASE.plusHours(1);
    jdbc.update("UPDATE notification SET read_at = ? WHERE id = ?", first, already);

    int changed = notificationRepository.markAllRead(ME, READ_AT);

    assertThat(changed).isZero();
    assertThat(readAtOf(already)).isEqualTo(first);
  }

  /**
   * 벌크 JPQL 은 영속성 컨텍스트를 지나지 않아 {@code @PreUpdate} 가 돌지 않는다. 질의가 직접 채우지 않으면 이 컬럼만 옛 값으로 남고, 그 사실은 행을
   * 다시 읽어야만 보인다.
   */
  @DisplayName("전체 읽음은 수정 시각도 함께 채운다.")
  @Test
  void markAllRead_fillsUpdatedAt() {
    long mine = notified(ME);

    notificationRepository.markAllRead(ME, READ_AT);

    assertThat(updatedAtOf(mine)).isEqualTo(READ_AT);
  }

  @DisplayName("안 읽은 수는 내 안 읽은 알림 건수와 같다.")
  @Test
  void countUnread() {
    notified(ME);
    long read = notified(ME);
    jdbc.update("UPDATE notification SET read_at = ? WHERE id = ?", READ_AT, read);

    assertThat(notificationRepository.countByRecipientIdAndReadAtIsNull(ME)).isEqualTo(1);
  }

  @DisplayName("남의 안 읽은 알림은 내 안 읽은 수에 들어가지 않는다.")
  @Test
  void countUnread_excludesOthers() {
    notified(SOMEONE_ELSE);
    notified(SOMEONE_ELSE);

    assertThat(notificationRepository.countByRecipientIdAndReadAtIsNull(ME)).isZero();
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

  private LocalDateTime updatedAtOf(long id) {
    return jdbc.queryForObject(
        "SELECT updated_at FROM notification WHERE id = ?", LocalDateTime.class, id);
  }
}
