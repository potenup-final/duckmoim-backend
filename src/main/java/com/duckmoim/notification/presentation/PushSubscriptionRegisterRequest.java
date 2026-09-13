package com.duckmoim.notification.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 구독 등록 (NT-12).
 *
 * <p><b>브라우저가 준 모양 그대로 받는다.</b> {@code PushSubscription.toJSON()} 의 결과가 이 형태라, 프론트가 값을 풀어 옮기는 단계가
 * 없으면 옮기다 틀릴 자리도 없다.
 *
 * @param endpoint 푸시 서비스가 발급한 주소. 이것이 곧 기기다
 */
public record PushSubscriptionRegisterRequest(
    @Schema(description = "푸시 서비스가 발급한 주소") @NotBlank @Size(max = 1024) String endpoint,
    @Schema(description = "본문을 암호화하는 데 쓰는 키 둘") @NotNull @Valid Keys keys) {

  /**
   * 암호화 키 둘.
   *
   * <p><b>식별값이 아니라 열쇠다.</b> 알림 본문을 그 기기만 열 수 있게 암호화하는 데 쓴다 — 로그에 남기지 않는다.
   */
  public record Keys(
      @Schema(description = "공개키") @NotBlank @Size(max = 255) String p256dh,
      @Schema(description = "인증 비밀") @NotBlank @Size(max = 255) String auth) {}
}
