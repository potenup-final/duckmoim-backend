package com.duckmoim.notification.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.notification.service.NotificationQueryService;
import com.duckmoim.notification.service.NotificationSlice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
 */
@Tag(name = "알림", description = "내 알림함")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

  private final NotificationQueryService notificationQueryService;

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
}
