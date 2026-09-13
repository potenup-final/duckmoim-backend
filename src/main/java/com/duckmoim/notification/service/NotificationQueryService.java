package com.duckmoim.notification.service;

import com.duckmoim.notification.domain.Notification;
import com.duckmoim.notification.domain.NotificationCursor;
import com.duckmoim.notification.domain.NotificationListQuery;
import com.duckmoim.notification.infra.NotificationRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림함 조회 (NT-08).
 *
 * <p><b>권한 판정이 없다.</b> 요청자 자신의 알림만 읽는 질의라 「볼 수 있는가」를 물을 상대가 없다 — 남의 알림을 가리킬 수 있는 경로를 두지 않았기 때문이다
 * (API-설계.md 「5. 결정 사항」 D-14). 그래서 {@code I-24} 가 판정이 아니라 <b>질의 모양</b>으로 지켜진다.
 *
 * <p><b>거를 것이 없어 읽은 것이 그대로 한 페이지다.</b> 댓글 목록이 「걸러지기 전의 마지막」을 커서로 삼아야 했던 것과 갈리는 지점이다 (CM-11) — 알림은
 * 상태로 빠지는 행이 없고, 만료된 알림은 목록에서 거르는 것이 아니라 배치가 지운다 (NT-11a).
 */
@Service
@RequiredArgsConstructor
public class NotificationQueryService {

  private final NotificationRepository notificationRepository;

  /** 내 알림을 한 페이지 읽는다. */
  @Transactional(readOnly = true)
  public NotificationSlice findMyNotifications(NotificationListQuery query) {
    List<Notification> read = notificationRepository.findSlice(query);

    boolean hasNext = read.size() > query.size();
    List<Notification> page = hasNext ? read.subList(0, query.size()) : read;

    return new NotificationSlice(views(page), nextCursor(page, hasNext), hasNext);
  }

  private static List<NotificationView> views(List<Notification> page) {
    return page.stream().map(NotificationQueryService::view).toList();
  }

  /** 「읽었나」의 판정은 도메인이 쥔다. 여기는 그 결과를 나른다. */
  private static NotificationView view(Notification notification) {
    return new NotificationView(
        notification.getId(),
        notification.getKind(),
        notification.getPostId(),
        notification.getCommentId(),
        notification.getRoomId(),
        notification.getMessageId(),
        !notification.isUnread(),
        notification.getCreatedAt());
  }

  /** 다음 페이지의 시작점. 이 페이지의 마지막 알림을 가리킨다. */
  private static NotificationCursor nextCursor(List<Notification> page, boolean hasNext) {
    if (!hasNext) {
      return null;
    }

    Notification last = page.get(page.size() - 1);
    return new NotificationCursor(last.getCreatedAt(), last.getId());
  }
}
