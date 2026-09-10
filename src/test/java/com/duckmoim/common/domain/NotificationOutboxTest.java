package com.duckmoim.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 아웃박스 행의 발행 규칙 (NT-01). 도메인이라 컨텍스트 없이 돈다. */
class NotificationOutboxTest {

  @DisplayName("아웃박스 행은 아직 보내지 않은 상태로 만들어진다.")
  @Test
  void of_isPending() {
    // when
    NotificationOutbox outbox =
        NotificationOutbox.of(NotificationKind.POST_COMMENTED, 1L, 10L, 100L);

    // then — 발행은 INSERT 로 끝나고 상태를 바꾸는 것은 워커다 (NT-02)
    assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
    assertThat(outbox.getKind()).isEqualTo(NotificationKind.POST_COMMENTED);
    assertThat(outbox.getRecipientId()).isEqualTo(1L);
    assertThat(outbox.getPostId()).isEqualTo(10L);
    assertThat(outbox.getCommentId()).isEqualTo(100L);
  }

  @DisplayName("수신자가 없으면 아웃박스 행을 만들 수 없다.")
  @Test
  void of_recipientIsMissing() {
    // when & then — 수신자는 워커가 나중에 채울 수 없는 값이다
    assertThatThrownBy(
            () -> NotificationOutbox.of(NotificationKind.POST_COMMENTED, null, 10L, 100L))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("아웃박스 행은 수신자를 가진다.");
  }
}
