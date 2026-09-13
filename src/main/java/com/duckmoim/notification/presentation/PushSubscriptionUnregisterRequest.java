package com.duckmoim.notification.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 구독 해제 (NT-12).
 *
 * <p><b>주소를 질의 문자열이 아니라 본문으로 받는다.</b> 그 값은 기기를 특정하는 개인정보라 (처리방침 제1조) URL 에 실으면 ALB 접근 로그와 브라우저
 * 히스토리에 그대로 남는다. 최대 1024자라 URL 길이도 부담이다.
 *
 * <p>{@code DELETE} 에 본문을 두는 것은 규격이 금지하지 않는다. 다만 중간 장비가 버릴 수 있다는 지적이 있어, 그 일이 실제로 생기면 명령형 경로
 * ({@code POST .../unsubscribe})로 옮기는 것이 대안이다.
 */
public record PushSubscriptionUnregisterRequest(
    @Schema(description = "끊을 기기의 주소") @NotBlank @Size(max = 1024) String endpoint) {}
