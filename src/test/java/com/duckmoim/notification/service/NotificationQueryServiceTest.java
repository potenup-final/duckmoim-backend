package com.duckmoim.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

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
 * 알림함 한 페이지를 어떻게 자르는가 (NT-08).
 *
 * <p>저장소 검사가 <b>무엇이 어떤 순서로 읽히는지</b>를 봤다면 (NotificationQueryRepositoryTest) 여기는 <b>한 건 더 읽은 것을 어떻게
 * 접는지</b>를 본다 — {@code hasNext} 와 {@code nextCursor} 가 그 산물이다.
 *
 * <p>수신자·아웃박스 번호를 남이 안 쓰는 대역에서 뗀 이유는 저장소 검사와 같다.
 */
@SpringBootTest
@Transactional
class NotificationQueryServiceTest {

  private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 14, 0, 0);

  private static final long ME = 91_001L;
  private static final long SOMEONE_ELSE = 91_002L;

  private final AtomicLong outboxIds = new AtomicLong(91_000);

  @Autowired private NotificationQueryService notificationQueryService;
  @Autowired private JdbcTemplate jdbc;

  @DisplayName("알림이 페이지 크기보다 적으면 다음 페이지가 없다.")
  @Test
  void findMyNotifications_isLastPage() {
    notified(ME, BASE.plusMinutes(1));
    notified(ME, BASE.plusMinutes(2));

    NotificationSlice slice = notificationQueryService.findMyNotifications(query(20));

    assertThat(slice.items()).hasSize(2);
    assertThat(slice.hasNext()).isFalse();
    assertThat(slice.nextCursor()).isNull();
  }

  /** 한 건을 더 읽어 다음 페이지 유무를 판정하므로, 그 한 건이 이번 페이지에 섞여 나가면 안 된다. */
  @DisplayName("더 읽은 한 건은 이번 페이지에 담기지 않는다.")
  @Test
  void findMyNotifications_hasNextPage() {
    notified(ME, BASE.plusMinutes(1));
    long middle = notified(ME, BASE.plusMinutes(2));
    long newest = notified(ME, BASE.plusMinutes(3));

    NotificationSlice slice = notificationQueryService.findMyNotifications(query(2));

    assertThat(idsOf(slice)).containsExactly(newest, middle);
    assertThat(slice.hasNext()).isTrue();
    assertThat(slice.nextCursor().id()).isEqualTo(middle);
  }

  @DisplayName("다음 커서로 이어 읽으면 앞 페이지가 다시 오지 않는다.")
  @Test
  void findMyNotifications_readsNextPage() {
    long oldest = notified(ME, BASE.plusMinutes(1));
    notified(ME, BASE.plusMinutes(2));
    notified(ME, BASE.plusMinutes(3));

    NotificationSlice first = notificationQueryService.findMyNotifications(query(2));
    NotificationSlice second =
        notificationQueryService.findMyNotifications(
            new NotificationListQuery(ME, first.nextCursor(), 2));

    assertThat(idsOf(second)).containsExactly(oldest);
    assertThat(second.hasNext()).isFalse();
  }

  @DisplayName("알림이 하나도 없으면 빈 페이지가 온다.")
  @Test
  void findMyNotifications_isEmpty() {
    notified(SOMEONE_ELSE, BASE.plusMinutes(1));

    NotificationSlice slice = notificationQueryService.findMyNotifications(query(20));

    assertThat(slice.items()).isEmpty();
    assertThat(slice.hasNext()).isFalse();
    assertThat(slice.nextCursor()).isNull();
  }

  private long notified(long recipientId, LocalDateTime createdAt) {
    jdbc.update(
        """
        INSERT INTO notification
            (outbox_id, recipient_id, kind, post_id, comment_id, created_at, updated_at)
        VALUES (?, ?, 'COMMENT_REPLIED', 10, 100, ?, ?)
        """,
        outboxIds.getAndIncrement(),
        recipientId,
        createdAt,
        createdAt);

    return jdbc.queryForObject("SELECT MAX(id) FROM notification", Long.class);
  }

  private static NotificationListQuery query(int size) {
    return new NotificationListQuery(ME, null, size);
  }

  private static List<Long> idsOf(NotificationSlice slice) {
    return slice.items().stream().map(NotificationView::id).toList();
  }
}
