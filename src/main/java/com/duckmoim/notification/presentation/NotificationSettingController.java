package com.duckmoim.notification.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.notification.service.NotificationSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 종류별 수신 설정 (NT-11).
 *
 * <p><b>알림함과 컨트롤러를 가른다.</b> 경로 접두어는 같지만 {@code NotificationController} 는 「내가 받은 것」을 다루고 여기는 「앞으로
 * 받을지」를 다룬다. 한 클래스에 두면 목록·읽음·배지·설정 넷이 섞인다.
 *
 * <p><b>회원번호가 경로에 없다.</b> 인증 주체에서 오므로 남의 설정을 가리킬 길이 자체가 없다 — 알림함이 {@code I-24} 를 지키는 방식과 같고
 * (API-설계.md 「5. 결정 사항」 D-14), 판정을 두는 대신 경로 모양으로 막는다.
 *
 * <p>권한이 {@code SIGNUP} 인 것은 알림함과 같다. 가입 미완료 유저는 알림이 생기지도 않아 끌 것이 없다 (AU-07).
 */
@Tag(name = "알림", description = "내 알림함")
@RestController
@RequestMapping("/api/v1/notifications/settings")
@RequiredArgsConstructor
public class NotificationSettingController {

  private final NotificationSettingService notificationSettingService;

  /**
   * 내 수신 설정을 읽는다 (NT-11).
   *
   * <p>한 번도 바꾸지 않았으면 셋 다 켜짐으로 나온다 — 저장된 것이 없다는 사실이 그대로 기본값이다.
   */
  @Operation(summary = "수신 설정 조회", description = "종류 셋이 모두 내려온다. 바꾼 적이 없으면 전부 켜짐이다.")
  @GetMapping
  public NotificationSettingResponse getMySettings(@AuthenticationPrincipal AuthUser authUser) {
    return NotificationSettingResponse.from(
        notificationSettingService.findMutedKinds(authUser.userId()));
  }

  /**
   * 내 수신 설정을 바꾼다 (NT-11).
   *
   * <p><b>동사가 {@code PUT} 이다.</b> 셋을 한 번에 보내므로 API 컨벤션의 「PUT = 전체 수정」이다. 같은 것을 두 번 보내도 결과가 같다.
   *
   * <p><b>이미 만들어진 알림은 그대로 있다.</b> 설정은 앞으로 올 것에만 걸린다 — 끄기 전에 받은 알림은 알림함에 남고 배지에도 센다.
   *
   * <p>성공은 본문 없는 200 이다 (컨벤션의 상태 코드 표에 204 가 없다). 보낸 것이 곧 저장된 것이라 돌려줄 값이 없다.
   */
  @Operation(summary = "수신 설정 저장", description = "종류 셋을 모두 보낸다. 이미 만들어진 알림은 지워지지 않는다.")
  @PutMapping
  public void replaceMySettings(
      @AuthenticationPrincipal AuthUser authUser,
      @Valid @RequestBody NotificationSettingRequest request) {

    notificationSettingService.replaceMutedKinds(authUser.userId(), request.toMutedKinds());
  }
}
