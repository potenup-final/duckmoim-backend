package com.duckmoim.notification.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.notification.service.NotificationQueryService;
import com.duckmoim.notification.service.NotificationReadService;
import com.duckmoim.notification.service.NotificationSlice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림함 (NT-08).
 *
 * <p><b>경로에 {@code /users/me} 가 없는데도 내 것만 나온다.</b> 「내 활동 내역」 탭들은 {@code /users/me} 아래에 있지만 알림은 남의
 * 것을 볼 경로가 아예 없어서 접두어로 소유를 표시할 이유가 없다 (API-설계.md 「5. 결정 사항」 D-14). 수신자는 경로가 아니라 인증 주체에서 온다.
 *
 * <p>권한이 {@code SIGNUP} 인 이유 — 알림은 댓글·답글에서 생기고 가입 미완료 유저는 댓글을 쓸 수도 받을 수도 없어 알림함이 항상 비어 있다 (AU-07).
 * 관문 판정은 {@code SecurityConfig} 와 {@code EndpointGradeTest} 의 권한 표가 지킨다.
 *
 * <p><b>개별 알림 조회를 두지 않는다.</b> 목록이 곧 본인 것뿐이라는 성질이 {@code I-24} 를 지키는 방식이고, 개별 경로를 열면 그 성질이 판정으로 바뀐다
 * — 그 불변식은 이중 방어가 없어 판정이 한 번 빠지면 아무것도 잡지 못한다.
 *
 * <p><b>읽음 처리는 {@code POST} 다</b> (NT-09). 명령형 엔드포인트라 API 컨벤션 「URL 규칙」의 {@code POST
 * /orders/{orderId}/cancel} 모양을 따른다 — 모집글 마감이 같은 자리에서 「상태를 {@code PATCH} 로 넘기지 않는다」고 정해 뒀다. {@code
 * D-14} 가 한때 {@code PATCH} 로 적어 뒀으나 그 결정이 정한 것은 404 냐 403 이냐였고, 동사는 정본이 {@code POST} 로 바로잡았다
 * (API-설계.md 「2-10. 알림 (Notification) · 2차」).
 */
@Tag(name = "알림", description = "내 알림함")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

  private final NotificationQueryService notificationQueryService;
  private final NotificationReadService notificationReadService;

  /**
   * 내 알림을 읽는다 (NT-08).
   *
   * <p><b>{@code authUser} 는 null 이 될 수 없다.</b> 이 경로가 {@code SIGNUP} 등급이라 익명 요청은 401, 가입 미완료는 403
   * 으로 관문에서 끝난다.
   */
  @Operation(summary = "알림함 조회", description = "최신순이다. 읽은 알림도 함께 온다.")
  @GetMapping
  public NotificationListResponse getMyNotifications(
      @AuthenticationPrincipal AuthUser authUser, NotificationListRequest request) {

    NotificationSlice slice =
        notificationQueryService.findMyNotifications(request.toQuery(authUser.userId()));

    return NotificationListResponse.from(slice);
  }

  /**
   * 알림 하나를 읽음으로 바꾼다 (NT-09).
   *
   * <p><b>남의 알림도 404 다</b> (D-14 ②). 403 이면 「그 번호의 알림이 존재한다」를 알려주게 되어 1장의 존재 은닉 원칙과 부딪힌다. 여기까지 오는
   * 동안 갈라 볼 값이 없다 — 저장소가 남의 것과 없는 것을 같은 빈 값으로 돌려준다.
   *
   * <p>성공은 본문 없는 200 이다 (컨벤션의 상태 코드 표에 204 가 없다). 바뀐 값은 목록이나 배지로 읽는다.
   */
  @Operation(summary = "알림 읽음", description = "이미 읽은 알림을 다시 읽어도 성공한다. 남의 알림과 없는 알림은 404 다.")
  @PostMapping("/{notificationId}/read")
  public void markRead(
      @AuthenticationPrincipal AuthUser authUser, @PathVariable Long notificationId) {

    notificationReadService.markRead(authUser.userId(), notificationId);
  }

  /**
   * 내 안 읽은 알림을 전부 읽음으로 바꾼다 (NT-09).
   *
   * <p>개별 읽음과 <b>같은 명령을 컬렉션에 건 것</b>이라 경로가 한 단어만 다르다. 경로 길이가 달라 {@link #markRead} 와 부딪히지 않는다.
   *
   * <p>안 읽은 알림이 하나도 없어도 성공이다. 성공은 본문 없는 200 이다.
   */
  @Operation(summary = "알림 전체 읽음", description = "내 안 읽은 알림을 모두 읽음으로 바꾼다. 읽을 것이 없어도 성공한다.")
  @PostMapping("/read")
  public void markAllRead(@AuthenticationPrincipal AuthUser authUser) {
    notificationReadService.markAllRead(authUser.userId());
  }

  /**
   * 안 읽은 알림 수 (NT-10).
   *
   * <p>알림함을 열지 않은 화면의 배지가 이것을 쓴다. 그래서 목록 응답에 얹지 않고 따로 낸다 ({@link NotificationUnreadCountResponse}).
   */
  @Operation(summary = "안 읽은 알림 수", description = "배지에 쓴다. 알림함을 열지 않아도 부를 수 있다.")
  @GetMapping("/unread-count")
  public NotificationUnreadCountResponse getUnreadCount(
      @AuthenticationPrincipal AuthUser authUser) {
    return NotificationUnreadCountResponse.of(
        notificationReadService.countUnread(authUser.userId()));
  }
}
