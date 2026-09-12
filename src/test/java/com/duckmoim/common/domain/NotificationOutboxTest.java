package com.duckmoim.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 아웃박스 행의 발행 규칙 (NT-01). 도메인이라 컨텍스트 없이 돈다. */
class NotificationOutboxTest {

  @DisplayName("아웃박스 행은 아직 보내지 않은 상태로 만들어진다.")
  @Test
  void of_isPending() {
    // when
    NotificationOutbox outbox =
        NotificationOutbox.of(
            NotificationKind.POST_COMMENTED, 1L, NotificationTarget.ofComment(10L, 100L));

    // then — 발행은 INSERT 로 끝나고 상태를 바꾸는 것은 워커다 (NT-02)
    assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
    assertThat(outbox.getKind()).isEqualTo(NotificationKind.POST_COMMENTED);
    assertThat(outbox.getRecipientId()).isEqualTo(1L);
    assertThat(outbox.getPostId()).isEqualTo(10L);
    assertThat(outbox.getCommentId()).isEqualTo(100L);
  }

  @DisplayName("보냈다고 적으면 상태가 SENT 가 된다.")
  @Test
  void markSent() {
    // given
    NotificationOutbox outbox = anOutbox();

    // when
    outbox.markSent();

    // then
    assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.SENT);
  }

  @DisplayName("이미 보낸 행을 다시 보냈다고 적을 수 없다.")
  @Test
  void markSent_alreadySent() {
    // given
    NotificationOutbox outbox = anOutbox();
    outbox.markSent();

    // when & then — 워커 둘이 같은 행을 집었다는 뜻이라 조용히 넘기지 않는다 (NT-04)
    assertThatThrownBy(outbox::markSent).isInstanceOf(IllegalStateException.class);
  }

  @DisplayName("발송이 실패하면 시도 횟수가 늘고 다음 시도가 밀린다.")
  @Test
  void failed() {
    // given
    NotificationOutbox outbox = anOutbox();
    LocalDateTime nextAttemptAt = LocalDateTime.of(2026, 9, 10, 0, 1);

    // when
    outbox.failed(nextAttemptAt);

    // then — 상태는 그대로다. 실패는 「아직 못 보냈다」의 한 형태다
    assertThat(outbox.getAttempts()).isEqualTo(1);
    assertThat(outbox.getNextAttemptAt()).isEqualTo(nextAttemptAt);
    assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
  }

  @DisplayName("정해진 횟수를 다 쓰면 DLQ 대상이 된다.")
  @Test
  void hasExhausted() {
    // given
    NotificationOutbox outbox = anOutbox();

    // when
    outbox.failed(LocalDateTime.of(2026, 9, 10, 0, 1));
    outbox.failed(LocalDateTime.of(2026, 9, 10, 0, 6));
    outbox.failed(LocalDateTime.of(2026, 9, 10, 0, 31));

    // then
    assertThat(outbox.hasExhausted(3)).isTrue();
  }

  @DisplayName("아직 시도가 남았으면 DLQ 대상이 아니다.")
  @Test
  void hasExhausted_hasAttemptsLeft() {
    // given
    NotificationOutbox outbox = anOutbox();

    // when
    outbox.failed(LocalDateTime.of(2026, 9, 10, 0, 1));

    // then
    assertThat(outbox.hasExhausted(3)).isFalse();
  }

  private static NotificationOutbox anOutbox() {
    return NotificationOutbox.of(
        NotificationKind.POST_COMMENTED, 1L, NotificationTarget.ofComment(10L, 100L));
  }

  @DisplayName("수신자가 없으면 아웃박스 행을 만들 수 없다.")
  @Test
  void of_recipientIsMissing() {
    // when & then — 수신자는 워커가 나중에 채울 수 없는 값이다
    assertThatThrownBy(
            () ->
                NotificationOutbox.of(
                    NotificationKind.POST_COMMENTED, null, NotificationTarget.ofComment(10L, 100L)))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("아웃박스 행은 수신자를 가진다.");
  }
}
