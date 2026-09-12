package com.duckmoim.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 알림이 가리키는 대상의 규칙 (NT-06 · NT-07). 도메인이라 컨텍스트 없이 돈다.
 *
 * <p><b>이 검사가 DB 의 CHECK 제약을 대신한다.</b> V806 이 네 컬럼을 전부 NULL 허용으로 두고 제약을 걸지 않기로 해서 (종류가 늘 때마다 세 표의
 * DDL 을 고치게 된다), 「어느 칸을 채우는가」를 지키는 자리가 이 팩터리 둘뿐이다.
 */
class NotificationTargetTest {

  @DisplayName("댓글 알림은 모집글과 댓글만 가리킨다.")
  @Test
  void ofComment() {
    // when
    NotificationTarget target = NotificationTarget.ofComment(10L, 100L);

    // then — 채팅 칸은 비어 있다
    assertThat(target.postId()).isEqualTo(10L);
    assertThat(target.commentId()).isEqualTo(100L);
    assertThat(target.roomId()).isNull();
    assertThat(target.messageId()).isNull();
  }

  @DisplayName("채팅 알림은 방과 메시지만 가리킨다.")
  @Test
  void ofRoomMessage() {
    // when
    NotificationTarget target = NotificationTarget.ofRoomMessage(3L, 777L);

    // then — 댓글 칸은 비어 있다
    assertThat(target.roomId()).isEqualTo(3L);
    assertThat(target.messageId()).isEqualTo(777L);
    assertThat(target.postId()).isNull();
    assertThat(target.commentId()).isNull();
  }

  @DisplayName("메시지 없이 채팅 알림의 대상을 만들 수 없다.")
  @Test
  void ofRoomMessage_messageIsMissing() {
    // when & then — 메시지마다 한 건이라 이 값이 없으면 같은 방에서 온 알림끼리 구분되지 않는다
    assertThatThrownBy(() -> NotificationTarget.ofRoomMessage(3L, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("채팅 알림은 메시지를 가리킨다.");
  }

  @DisplayName("모집글 없이 댓글 알림의 대상을 만들 수 없다.")
  @Test
  void ofComment_postIsMissing() {
    // when & then — 워커는 Companion 에 물어볼 수 없어 나중에 채울 수 없는 값이다
    assertThatThrownBy(() -> NotificationTarget.ofComment(null, 100L))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("댓글 알림은 모집글을 가리킨다.");
  }
}
