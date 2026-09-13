package com.duckmoim.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.common.domain.NotificationKind;
import com.duckmoim.common.domain.NotificationTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 알림 한 건이 만들어지는 규칙 (NT-02). 도메인이라 컨텍스트 없이 돈다.
 *
 * <p><b>읽음 전이가 여기 없다.</b> 「이미 읽었나」를 메모리에서 판정하면 조회와 커밋 사이에 전체 읽음이 지나갔을 때 먼저 찍힌 시각을 덮어쓴다. 그래서 조건부
 * UPDATE 가 쥐고 있고, 검사도 MySQL 위에 있다 ({@code NotificationReadRepositoryTest} · {@code
 * NotificationReadRaceTest}).
 */
class NotificationTest {

  @DisplayName("알림은 안 읽은 상태로 만들어진다.")
  @Test
  void of_isUnread() {
    // when
    Notification notification =
        Notification.of(
            1L, 7L, NotificationKind.POST_COMMENTED, NotificationTarget.ofComment(10L, 100L));

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
    assertThatThrownBy(
            () ->
                Notification.of(
                    null,
                    7L,
                    NotificationKind.POST_COMMENTED,
                    NotificationTarget.ofComment(10L, 100L)))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("알림은 어느 발행에서 나왔는지를 가진다.");
  }

  @DisplayName("채팅 알림은 모집글·댓글 없이 방과 메시지를 가리킨다.")
  @Test
  void of_roomMessaged() {
    // when
    Notification notification =
        Notification.of(
            1L, 7L, NotificationKind.ROOM_MESSAGED, NotificationTarget.ofRoomMessage(3L, 777L));

    // then — 해당 없는 칸은 비어 있다 (V806)
    assertThat(notification.getRoomId()).isEqualTo(3L);
    assertThat(notification.getMessageId()).isEqualTo(777L);
    assertThat(notification.getPostId()).isNull();
    assertThat(notification.getCommentId()).isNull();
  }
}
