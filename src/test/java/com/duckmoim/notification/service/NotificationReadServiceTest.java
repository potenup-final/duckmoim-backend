package com.duckmoim.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.notification.exception.NotificationErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 읽음 처리와 배지가 맞물리는 자리 (NT-09 · NT-10).
 *
 * <p>두 요구사항의 검증 기준이 서로를 가리킨다 — NT-09 는 「읽은 뒤 <b>안 읽은 수가 준다</b>」 이고 NT-10 은 「<b>실제 안 읽은 건수</b>와 일치」
 * 다. 그래서 읽음과 세기를 따로 보지 않고 <b>읽은 다음 세어서</b> 본다.
 *
 * <p>수신자·아웃박스 번호를 남이 안 쓰는 대역에서 뗀 이유는 다른 알림 검사들과 같다.
 */
@SpringBootTest
@Transactional
class NotificationReadServiceTest {

  private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 14, 0, 0);

  private static final long ME = 94_001L;
  private static final long SOMEONE_ELSE = 94_002L;
  private static final long NO_SUCH_NOTIFICATION = 94_999_999L;

  private final AtomicLong outboxIds = new AtomicLong(94_000);

  @Autowired private NotificationReadService notificationReadService;
  @Autowired private JdbcTemplate jdbc;

  /** {@link #readAtOf} 가 쓴다. 이유는 그쪽에 적혀 있다. */
  @PersistenceContext private EntityManager entityManager;

  @DisplayName("알림 하나를 읽으면 안 읽은 수가 하나 준다.")
  @Test
  void markRead() {
    long first = notified(ME);
    notified(ME);

    notificationReadService.markRead(ME, first);

    assertThat(notificationReadService.countUnread(ME)).isEqualTo(1);
    assertThat(readAtOf(first)).isNotNull();
  }

  /** 배지를 지우는 동작이라 몇 번을 불러도 같은 결과여야 한다. 409 가 아니다. */
  @DisplayName("이미 읽은 알림을 다시 읽어도 성공한다.")
  @Test
  void markRead_alreadyRead() {
    long mine = notified(ME);
    notificationReadService.markRead(ME, mine);
    LocalDateTime first = readAtOf(mine);

    notificationReadService.markRead(ME, mine);

    assertThat(readAtOf(mine)).isEqualTo(first);
    assertThat(notificationReadService.countUnread(ME)).isZero();
  }

  /**
   * <b>남의 알림이 없는 알림과 같은 응답으로 끝난다.</b> 403 이면 「그 번호의 알림이 존재한다」를 알려주게 되어 존재 은닉 원칙과 부딪힌다 (API-설계.md
   * 「5. 결정 사항」 D-14 ②).
   */
  @DisplayName("남의 알림을 읽으려 하면 알림을 찾을 수 없다고 답한다.")
  @Test
  void markRead_notificationIsNotMine() {
    long theirs = notified(SOMEONE_ELSE);

    assertThatThrownBy(() -> notificationReadService.markRead(ME, theirs))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(NotificationErrorCode.NOTIFICATION_NOT_FOUND);

    assertThat(readAtOf(theirs)).isNull();
  }

  @DisplayName("없는 알림을 읽으려 하면 알림을 찾을 수 없다고 답한다.")
  @Test
  void markRead_notificationIsMissing() {
    assertThatThrownBy(() -> notificationReadService.markRead(ME, NO_SUCH_NOTIFICATION))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
  }

  @DisplayName("전체 읽음을 부르면 안 읽은 수가 0 이 된다.")
  @Test
  void markAllRead() {
    notified(ME);
    notified(ME);
    notified(ME);

    notificationReadService.markAllRead(ME);

    assertThat(notificationReadService.countUnread(ME)).isZero();
  }

  /** {@code I-24} 를 쓰기 쪽에서 보는 자리다. 이 불변식은 이중 방어가 없어 질의 조건 하나가 유일한 방어선이다. */
  @DisplayName("전체 읽음은 남의 알림을 건드리지 않는다.")
  @Test
  void markAllRead_excludesOthers() {
    long theirs = notified(SOMEONE_ELSE);
    notified(ME);

    notificationReadService.markAllRead(ME);

    assertThat(readAtOf(theirs)).isNull();
    assertThat(notificationReadService.countUnread(SOMEONE_ELSE)).isEqualTo(1);
  }

  /** 안 읽은 알림이 하나도 없어도 성공이다. 몇 번을 불러도 같은 결과여야 하는 동작이다. */
  @DisplayName("읽을 알림이 없어도 전체 읽음은 성공한다.")
  @Test
  void markAllRead_nothingIsUnread() {
    notificationReadService.markAllRead(ME);

    assertThat(notificationReadService.countUnread(ME)).isZero();
  }

  @DisplayName("안 읽은 수는 내 안 읽은 알림 건수와 같다.")
  @Test
  void countUnread() {
    notified(ME);
    notified(ME);
    notified(SOMEONE_ELSE);

    assertThat(notificationReadService.countUnread(ME)).isEqualTo(2);
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

  /**
   * <b>먼저 flush 한다.</b> 개별 읽음은 영속성 컨텍스트에 올라온 엔티티를 고치고, 그 변경은 커밋이나 JPQL 실행 전까지 DB 에 안 내려간다.
   * JdbcTemplate 은 같은 커넥션을 쓰면서도 그 대기 중인 변경을 못 봐서, flush 없이 읽으면 방금 찍은 시각이 NULL 로 보인다.
   *
   * <p>SQL 로 직접 읽는 것은 <b>도메인이 쥔 값이 아니라 표에 실제로 저장된 값</b>을 보기 위해서다 — 응답에 {@code read_at} 이 나가지 않으므로
   * (API-설계.md 「2-10. 알림 (Notification) · 2차」) 여기 말고는 확인할 자리가 없다.
   */
  private LocalDateTime readAtOf(long id) {
    entityManager.flush();

    return jdbc.queryForObject(
        "SELECT read_at FROM notification WHERE id = ?", LocalDateTime.class, id);
  }
}
