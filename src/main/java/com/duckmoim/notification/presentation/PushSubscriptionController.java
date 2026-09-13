package com.duckmoim.notification.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.notification.service.PushSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 웹 푸시 구독 (NT-12).
 *
 * <p><b>회원번호가 경로에 없다.</b> 인증 주체에서 오므로 남의 기기를 등록하거나 끊을 길이 자체가 없다 — 알림함이 {@code I-24} 를 지키는 방식과 같다
 * (API-설계.md 「5. 결정 사항」 D-14).
 *
 * <p>권한이 {@code SIGNUP} 인 것은 알림함과 같다. 가입 미완료 유저는 알림이 생기지도 않아 받을 것이 없다 (AU-07).
 *
 * <p><b>둘 다 응답 본문이 없다</b> (빈 본문 200). 등록은 보낸 것이 곧 저장된 것이고, 해제는 없는 것을 지워도 성공이라 돌려줄 상태가 없다 — 읽음
 * 처리(NT-09)와 같은 자리다.
 */
@Tag(name = "알림", description = "내 알림함")
@RestController
@RequestMapping("/api/v1/push-subscriptions")
@RequiredArgsConstructor
public class PushSubscriptionController {

  private final PushSubscriptionService pushSubscriptionService;

  /**
   * 이 기기로 푸시를 받는다 (NT-12).
   *
   * <p><b>같은 기기가 다시 등록해도 한 건이다.</b> 브라우저가 구독을 갈 때마다 ({@code pushsubscriptionchange}) 다시 보내는 것이 정상
   * 경로라, 쌓이면 한 기기에 같은 알림이 여러 번 간다.
   */
  @Operation(summary = "구독 등록", description = "같은 기기가 다시 보내면 키만 갱신한다.")
  @PostMapping
  public void register(
      @AuthenticationPrincipal AuthUser authUser,
      @Valid @RequestBody PushSubscriptionRegisterRequest request) {

    pushSubscriptionService.register(
        authUser.userId(), request.endpoint(), request.keys().p256dh(), request.keys().auth());
  }

  /**
   * 이 기기만 끊는다 (NT-12).
   *
   * <p>「기기 둘에서 등록하면 둘 다 받는다」가 요구사항이라 <b>한 기기만 끄는 길</b>이 있어야 한다.
   */
  @Operation(summary = "구독 해제", description = "없는 구독을 지워도 성공이다.")
  @DeleteMapping
  public void unregister(
      @AuthenticationPrincipal AuthUser authUser,
      @Valid @RequestBody PushSubscriptionUnregisterRequest request) {

    pushSubscriptionService.unregister(authUser.userId(), request.endpoint());
  }
}
