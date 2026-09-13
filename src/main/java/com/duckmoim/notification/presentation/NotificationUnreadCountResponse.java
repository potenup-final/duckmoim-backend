package com.duckmoim.notification.presentation;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 배지에 그릴 숫자 (NT-10).
 *
 * <p><b>숫자 하나인데도 객체로 감싼다.</b> 벌거벗은 숫자를 내보내면 나중에 필드를 더할 때 응답 모양 자체가 바뀌어 기존 클라이언트가 깨진다. API 컨벤션이 「내부
 * 도메인 모델을 API 응답으로 직접 노출하지 않는다」고 정한 것과 같은 방향이다.
 *
 * <p><b>알림함 목록 응답에 얹지 않는다.</b> 배지는 알림함을 열지 않은 화면에도 떠 있어야 해서 목록과 호출 시점이 다르다 (API-설계.md 「2-10. 알림
 * (Notification) · 2차」). 채팅 방 목록의 {@code unreadCount} 와 갈리는 지점이다 — 그쪽은 방마다 수가 달라 목록 항목의 필드지만
 * (CH-13) 알림은 수신자당 하나라 목록의 필드가 될 자리가 없다.
 */
public record NotificationUnreadCountResponse(
    @Schema(description = "안 읽은 알림 수", example = "3") long unreadCount) {

  static NotificationUnreadCountResponse of(long unreadCount) {
    return new NotificationUnreadCountResponse(unreadCount);
  }
}
