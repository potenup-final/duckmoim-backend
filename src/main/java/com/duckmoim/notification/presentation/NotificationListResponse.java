package com.duckmoim.notification.presentation;

import com.duckmoim.notification.domain.NotificationCursor;
import com.duckmoim.notification.service.NotificationSlice;
import java.util.List;

/**
 * 알림함 응답 (NT-08).
 *
 * <p>커서 페이지네이션 응답은 {@code items} · {@code nextCursor} · {@code hasNext} 셋이다 (API-컨벤션.md 「공통 응답
 * 형식」). 성공 응답을 {@code data} 로 감싸지 않는다.
 *
 * <p><b>{@code items} 는 {@code size} 만큼 온다.</b> 댓글 목록이 적게 올 수 있다고 밝힌 것은 자리표시자를 목록에서 빼기 때문인데 (CM-11)
 * 알림은 조회 뒤에 걸러 내는 것이 없다.
 */
public record NotificationListResponse(
    List<NotificationItemResponse> items, String nextCursor, boolean hasNext) {

  static NotificationListResponse from(NotificationSlice slice) {
    return new NotificationListResponse(
        slice.items().stream().map(NotificationItemResponse::from).toList(),
        encoded(slice.nextCursor()),
        slice.hasNext());
  }

  private static String encoded(NotificationCursor cursor) {
    return cursor == null ? null : cursor.encode();
  }
}
