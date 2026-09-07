package com.duckmoim.auth.domain;

public interface TokenProvider {

  String issueAccessToken(AuthUser authUser);

  AuthUser readAccessToken(String accessToken);

  /**
   * Refresh 토큰을 발급한다 (AU-02 Refresh 14일).
   *
   * <p><b>회원번호만 담는다.</b> {@code signupCompleted} 와 {@code admin} 은 14일 안에 바뀌므로(AU-05 가입 완료, 관리자 지정)
   * 재발급 때 DB 에서 다시 읽는다. 토큰에 박아 두면 가입을 마친 사용자가 2주 동안 가입 미완료로 취급된다.
   */
  String issueRefreshToken(Long userId);

  /**
   * Refresh 토큰에서 회원번호를 읽는다.
   *
   * <p><b>토큰 자신이 회원번호를 들고 있어야 한다.</b> {@code POST /api/v1/auth/token} 은 등급이 {@code PUBLIC} 이라(API
   * 설계 2-1) Access 토큰이 함께 오지 않고, 재사용 탐지는 이미 행이 지워진 뒤에 「누구 것인가」를 알아야 한다 — AU-03 의 「해당 유저 전체 폐기」가 그
   * 회원번호로 돈다.
   */
  Long readRefreshToken(String refreshToken);
}
