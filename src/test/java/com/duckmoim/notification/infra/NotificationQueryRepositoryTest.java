package com.duckmoim.notification.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.notification.domain.Notification;
import com.duckmoim.notification.domain.NotificationCursor;
import com.duckmoim.notification.domain.NotificationListQuery;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림함 목록의 정렬 · 커서 경계 · 수신자 격리 (NT-08).
 *
 * <p>실제 MySQL 로 돈다. 커서 경계는 정렬과 튜플 비교가 개입해서 mock 으로는 검증되지 않고, 테스트 컨벤션이 H2 도 금지했다.
 *
 * <p><b>생성 시각을 손으로 고정한다.</b> 정렬 키가 같은 데이터를 만들어 경계를 봐야 하는데 엔티티로 저장하면 마이크로초가 갈려 같은 시각이 만들어지지 않는다. 워커가
 * 한 주기에 여러 건을 보내면 실제로 같은 시각이 나온다.
 */
@SpringBootTest
@Transactional
class NotificationQueryRepositoryTest {

  private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 14, 0, 0);

  /**
   * <b>다른 테스트가 쓰지 않는 번호를 쓴다.</b> 이 검사는 「내 목록이 정확히 이것뿐」을 단언하므로, 같은 수신자의 행이 하나라도 남아 있으면 깨진다. 발송 쪽 검사가
   * 트랜잭션 없이 돌며 행을 커밋했다 지우는데 (NotificationDispatchServiceTest) 그 번호와 겹치면 실행 순서에 따라 결과가 갈린다 —
   * STAR-118 에서 남의 행 102 개에 실제로 물렸다.
   *
   * <p>수신자는 FK 가 없는 ID 참조라 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」) 시드에 없는 번호를 써도 된다.
   */
  private static final long ME = 90_001L;

  private static final long SOMEONE_ELSE = 90_002L;

  /** 아웃박스 번호는 유니크 제약이 걸려 있어 건마다 달라야 한다 (V801). 수신자와 같은 이유로 남이 안 쓸 대역에서 뗀다. */
  private final AtomicLong outboxIds = new AtomicLong(90_000);

  @Autowired private NotificationRepository notificationRepository;
  @Autowired private JdbcTemplate jdbc;

  @DisplayName("알림은 최신순으로 온다.")
  @Test
  void findSlice() {
    long oldest = notified(ME, BASE.plusMinutes(1));
    long newest = notified(ME, BASE.plusMinutes(3));
    long middle = notified(ME, BASE.plusMinutes(2));

    List<Notification> found = notificationRepository.findSlice(query(null, 20));

    assertThat(idsOf(found)).containsExactly(newest, middle, oldest);
  }

  /**
   * {@code I-24} 가 지켜지는 것을 보는 자리다.
   *
   * <p>이 불변식은 이중 방어가 없어 조회 조건 하나가 유일한 방어선이다 (도메인-모델링.md 「5. 불변식」). 남의 알림을 가리키는 경로를 아예 두지 않았으므로
   * (API-설계.md 「5. 결정 사항」 D-14) 403 이 날 자리가 없고, <b>검증은 「내 목록에 나오지 않는다」가 된다.</b>
   */
  @DisplayName("남의 알림은 내 목록에 나오지 않는다.")
  @Test
  void findSlice_excludesOthers() {
    long mine = notified(ME, BASE.plusMinutes(1));
    notified(SOMEONE_ELSE, BASE.plusMinutes(2));
    notified(SOMEONE_ELSE, BASE.plusMinutes(3));

    List<Notification> found = notificationRepository.findSlice(query(null, 20));

    assertThat(idsOf(found)).containsExactly(mine);
  }

  /** 알림함은 읽은 것도 함께 보인다. 안 읽은 것만 세는 값은 배지이고 그것은 NT-10 이다. */
  @DisplayName("읽은 알림도 목록에 남는다.")
  @Test
  void findSlice_keepsRead() {
    long unread = notified(ME, BASE.plusMinutes(1));
    long read = notified(ME, BASE.plusMinutes(2));
    jdbc.update("UPDATE notification SET read_at = ? WHERE id = ?", BASE.plusMinutes(5), read);

    List<Notification> found = notificationRepository.findSlice(query(null, 20));

    assertThat(idsOf(found)).containsExactly(read, unread);
  }

  @DisplayName("다음 페이지 유무를 알려고 한 건을 더 읽는다.")
  @Test
  void findSlice_readsOneMore() {
    notified(ME, BASE.plusMinutes(1));
    notified(ME, BASE.plusMinutes(2));
    notified(ME, BASE.plusMinutes(3));

    assertThat(notificationRepository.findSlice(query(null, 2))).hasSize(3);
  }

  /** 정렬 키가 같은 데이터를 일부러 만들어 경계를 본다 (테스트 컨벤션). 워커가 한 주기에 여러 건을 보내면 실제로 이렇게 된다. */
  @DisplayName("생성 시각이 같아도 id 가 순서를 정한다.")
  @Test
  void findSlice_hasSameCreatedAt() {
    long first = notified(ME, BASE);
    long second = notified(ME, BASE);
    long third = notified(ME, BASE);

    List<Notification> page = notificationRepository.findSlice(query(null, 2));
    assertThat(idsOf(page)).containsExactly(third, second, first);

    List<Notification> next =
        notificationRepository.findSlice(query(new NotificationCursor(BASE, second), 2));

    assertThat(idsOf(next)).containsExactly(first);
  }

  @DisplayName("커서 다음부터 이어 읽고 앞 페이지를 다시 주지 않는다.")
  @Test
  void findSlice_afterCursor() {
    long oldest = notified(ME, BASE.plusMinutes(1));
    long middle = notified(ME, BASE.plusMinutes(2));
    long newest = notified(ME, BASE.plusMinutes(3));

    List<Notification> next =
        notificationRepository.findSlice(
            query(new NotificationCursor(BASE.plusMinutes(3), newest), 20));

    assertThat(idsOf(next)).containsExactly(middle, oldest);
  }

  /** 커서가 남의 알림을 가리켜도 내 목록만 이어 읽는다. 수신자 조건이 커서보다 먼저 걸린다. */
  @DisplayName("남의 알림을 가리키는 커서로도 남의 알림이 나오지 않는다.")
  @Test
  void findSlice_cursorPointsAtOthers() {
    long mine = notified(ME, BASE.plusMinutes(1));
    long theirs = notified(SOMEONE_ELSE, BASE.plusMinutes(3));

    List<Notification> next =
        notificationRepository.findSlice(
            query(new NotificationCursor(BASE.plusMinutes(3), theirs), 20));

    assertThat(idsOf(next)).containsExactly(mine);
  }

  private long notified(long recipientId, LocalDateTime createdAt) {
    jdbc.update(
        """
        INSERT INTO notification
            (outbox_id, recipient_id, kind, post_id, comment_id, created_at, updated_at)
        VALUES (?, ?, 'POST_COMMENTED', 10, 100, ?, ?)
        """,
        outboxIds.getAndIncrement(),
        recipientId,
        createdAt,
        createdAt);

    return jdbc.queryForObject("SELECT MAX(id) FROM notification", Long.class);
  }

  private static NotificationListQuery query(NotificationCursor cursor, int size) {
    return new NotificationListQuery(ME, cursor, size);
  }

  private static List<Long> idsOf(List<Notification> found) {
    return found.stream().map(Notification::getId).toList();
  }
}
