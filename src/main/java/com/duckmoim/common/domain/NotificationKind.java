package com.duckmoim.common.domain;

/**
 * 아웃박스에 쌓이는 알림의 종류 (NT-01 · NT-06).
 *
 * <p><b>셋 중 둘이다.</b> 2차-MVP-기능-명세서.md 「2-2. 알림 (NT)」 이 정한 종류는 셋인데, 셋째(채팅방 새 메시지)는 행을 넣는 쪽인 {@code
 * Message} 가 아직 없다. 쓰는 코드가 없는 값을 미리 두지 않는다.
 *
 * <p><b>{@code common} 에 있는 이유는 의존 방향이다.</b> 도메인-모델링.md 「2. 바운디드 컨텍스트」 의 화살표가 Notification 에서
 * Identity 로 하나만 나가 Notification 은 Companion 을 모른다. 이 값을 Notification 안에 두면 행을 넣는 Companion 이
 * Notification 을 참조하게 되어 그 화살표가 뒤집힌다.
 */
public enum NotificationKind {

  /** 내 모집글에 댓글이 달렸다. 수신자는 모집글 방장이다. */
  POST_COMMENTED,

  /** 내 댓글에 답글이 달렸다. 수신자는 부모 댓글 작성자다. */
  COMMENT_REPLIED
}
