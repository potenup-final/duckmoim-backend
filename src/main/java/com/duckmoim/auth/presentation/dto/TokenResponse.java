package com.duckmoim.auth.presentation.dto;

import com.duckmoim.auth.service.AuthToken;

/**
 * 토큰 발급·재발급 응답 (AU-02 · AU-03).
 *
 * <p>{@code signupCompleted} 에 {@code is} 를 붙이지 않는다 (API-컨벤션.md 「표기 규칙」).
 */
public record TokenResponse(String accessToken, String refreshToken, boolean signupCompleted) {

  public static TokenResponse from(AuthToken authToken) {
    return new TokenResponse(
        authToken.accessToken(), authToken.refreshToken(), authToken.signupCompleted());
  }
}
