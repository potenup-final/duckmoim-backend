package com.duckmoim.common.domain;

import java.util.Objects;

/**
 * 알림이 가리키는 대상 (NT-06).
 *
 * <p><b>종류마다 채워지는 칸이 다르다.</b> 댓글 알림 둘은 모집글·댓글을 가리키고 채팅 알림은 방·메시지를 가리킨다. 해당 없는 쪽은 비어 있고, 그것이 {@code
 * V806} 이 네 컬럼을 전부 NULL 허용으로 둔 이유다 (API-설계.md 「채팅 알림 (NT-07)」).
 *
 * <p><b>넷을 한 덩어리로 묶는 것은 나르는 거리가 길기 때문이다.</b> 같은 값이 아웃박스에서 알림함으로, 못 보내면 DLQ 로, 조회에서 응답으로 그대로 옮겨진다.
 * 낱개로 넘기면 종류가 하나 늘 때마다 그 경로의 시그니처가 전부 늘어난다.
 *
 * <p><b>정적 팩터리가 「어느 칸을 채우는가」를 쥔다.</b> 생성자를 열어 두면 네 칸을 임의로 섞을 수 있는데, DB 에 CHECK 제약을 두지 않기로 해서 (V806)
 * 여기가 그 판정의 유일한 자리다.
 */
public record NotificationTarget(Long postId, Long commentId, Long roomId, Long messageId) {

  /** 내 모집글에 댓글이 달렸다 · 내 댓글에 답글이 달렸다 (NT-06). */
  public static NotificationTarget ofComment(Long postId, Long commentId) {
    Objects.requireNonNull(postId, "댓글 알림은 모집글을 가리킨다.");
    Objects.requireNonNull(commentId, "댓글 알림은 댓글을 가리킨다.");

    return new NotificationTarget(postId, commentId, null, null);
  }

  /**
   * 채팅방에 새 메시지가 있다 (NT-07).
   *
   * <p><b>메시지 번호를 함께 받는다.</b> 메시지마다 한 건이라 (묶음을 넣지 않는다) 이 값이 없으면 같은 방에서 온 알림끼리 구분되지 않고, 못 보낸 건이 DLQ
   * 로 갔을 때 무엇이었는지 남지 않는다.
   */
  public static NotificationTarget ofRoomMessage(Long roomId, Long messageId) {
    Objects.requireNonNull(roomId, "채팅 알림은 방을 가리킨다.");
    Objects.requireNonNull(messageId, "채팅 알림은 메시지를 가리킨다.");

    return new NotificationTarget(null, null, roomId, messageId);
  }
}
