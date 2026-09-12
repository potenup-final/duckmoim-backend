package com.duckmoim.chat.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 서명 발급 요청 (CH-14).
 *
 * <p><b>클라이언트가 선언한 값이다.</b> 서버가 파일을 못 보므로 발급 시점의 판정 재료가 이 둘뿐이고, <b>거짓말일 수 있다</b> — 확정 단계가 저장소에 물어
 * 실제 값을 다시 본다.
 *
 * <p>허용 목록과 상한은 설정이라 여기에 적지 않는다. {@code @NotBlank} · {@code @Positive} 만 두고 정책 판정은 {@code
 * ChatImagePolicy} 가 한다 — 같은 규칙을 발급과 확정 두 시점에 써야 해서 애너테이션으로는 절반만 덮인다.
 */
public record ChatImageUploadRequest(
    @Schema(description = "올릴 파일의 형식", example = "image/jpeg") @NotBlank(message = "이미지 형식은 필수입니다.")
        String contentType,
    @Schema(description = "올릴 파일의 바이트 크기", example = "204800")
        @Positive(message = "이미지 크기는 1바이트 이상이어야 합니다.")
        long contentLength) {}
