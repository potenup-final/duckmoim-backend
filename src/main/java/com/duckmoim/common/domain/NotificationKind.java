package com.duckmoim.common.domain;

/**
 * 아웃박스에 쌓이는 알림의 종류 (NT-01 · NT-06).
 *
 * <p><b>셋이다.</b> 2차-MVP-기능-명세서.md 「2-2. 알림 (NT)」 이 정한 종류가 셋이고, 마지막까지 비어 있던 채팅방 새 메시지를 NT-07 이 채웠다 —
 * 행을 넣는 쪽인 {@code Message} 가 그때 생겼다.
 *
 * <p><b>가리키는 대상이 종류마다 다르다.</b> 앞의 둘은 모집글·댓글을, 채팅 알림은 방·메시지를 가리킨다. 그 갈림은 {@link NotificationTarget}
 * 이 쥐고 있다.
 *
 * <p><b>{@code common} 에 있는 이유는 의존 방향이다.</b> 도메인-모델링.md 「2. 바운디드 컨텍스트」 의 화살표가 Notification 에서
 * Identity 로 하나만 나가 Notification 은 Companion 을 모른다. 이 값을 Notification 안에 두면 행을 넣는 Companion 이
 * Notification 을 참조하게 되어 그 화살표가 뒤집힌다.
 */
public enum NotificationKind {

  /** 내 모집글에 댓글이 달렸다. 수신자는 모집글 방장이다. */
  POST_COMMENTED,

  /** 내 댓글에 답글이 달렸다. 수신자는 부모 댓글 작성자다. */
  COMMENT_REPLIED,

  /**
   * 채팅방에 새 메시지가 있다 (NT-07). 수신자는 그 방의 멤버다.
   *
   * <p><b>메시지마다 한 건이고 묶음이 없다</b> (2026-09-10 결정 안건 2-4). 대신 <b>지금 그 방을 보고 있는 멤버는 수신자에서 빠진다</b> — 이미
   * 화면에 떠 있는 말풍선을 배지가 한 번 더 세게 되기 때문이다.
   *
   * <p><b>그 판정은 여기가 아니라 넣는 쪽이 한다.</b> 「보고 있다」는 채팅의 사실이고, 이 값이 사는 {@code common} 이 Chat 을 참조하면 의존이
   * 거꾸로 흐른다.
   */
  ROOM_MESSAGED
}
