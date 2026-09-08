package com.duckmoim.auth.presentation.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 재발급 요청 (AU-03).
 *
 * <p>Refresh 토큰을 <b>헤더가 아니라 본문</b>으로 받는다. {@code Authorization} 헤더는 Access 자리이고, 둘을 같은 자리로 받으면 어느
 * 것을 보낸 건지 서버가 알 수 없다 (0001-토큰-전달-방식.md 「선택」).
 */
public record TokenRefreshRequest(@NotBlank(message = "리프레시 토큰은 필수입니다.") String refreshToken) {}
