package com.duckmoim.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.common.domain.NotificationKind;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 알림 한 건이 만들어지고 읽히는 규칙 (NT-02 · NT-09). 도메인이라 컨텍스트 없이 돈다. */
class NotificationTest {

  private static final LocalDateTime FIRST = LocalDateTime.of(2026, 9, 14, 0, 0);
  private static final LocalDateTime LATER = FIRST.plusHours(3);

  @DisplayName("알림은 안 읽은 상태로 만들어진다.")
  @Test
  void of_isUnread() {
    // when
    Notification notification = Notification.of(1L, 7L, NotificationKind.POST_COMMENTED, 10L, 100L);

    // then — 읽음으로 바꾸는 문은 NT-09 가 낸다
    assertThat(notification.isUnread()).isTrue();
    assertThat(notification.getReadAt()).isNull();
    assertThat(notification.getOutboxId()).isEqualTo(1L);
    assertThat(notification.getRecipientId()).isEqualTo(7L);
    assertThat(notification.getKind()).isEqualTo(NotificationKind.POST_COMMENTED);
  }

  @DisplayName("어느 발행에서 나왔는지 없이 알림을 만들 수 없다.")
  @Test
  void of_outboxIdIsMissing() {
    // when & then — 이 값이 없으면 같은 발행으로 알림이 두 번 만들어지는 것을 막을 수 없다
    assertThatThrownBy(() -> Notification.of(null, 7L, NotificationKind.POST_COMMENTED, 10L, 100L))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("알림은 어느 발행에서 나왔는지를 가진다.");
  }

  @DisplayName("안 읽은 알림을 읽으면 읽은 시각이 남는다.")
  @Test
  void markRead_stampsTime() {
    // given
    Notification notification = unread();

    // when
    notification.markRead(FIRST);

    // then
    assertThat(notification.isUnread()).isFalse();
    assertThat(notification.getReadAt()).isEqualTo(FIRST);
  }

  @DisplayName("이미 읽은 알림을 다시 읽어도 처음 읽은 시각이 유지된다.")
  @Test
  void markRead_keepsFirstStamp() {
    // given
    Notification notification = unread();
    notification.markRead(FIRST);

    // when — 두 번 누르거나, 개별 읽음 뒤에 전체 읽음이 지나가는 경우다
    notification.markRead(LATER);

    // then — 예외가 아니라 그대로 둔다. 갱신하면 「언제 읽었나」를 남긴 이유가 사라진다
    assertThat(notification.getReadAt()).isEqualTo(FIRST);
  }

  private static Notification unread() {
    return Notification.of(1L, 7L, NotificationKind.POST_COMMENTED, 10L, 100L);
  }
}
