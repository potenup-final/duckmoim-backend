package com.duckmoim.auth.domain;

import java.time.LocalDateTime;

public interface TokenProvider {

  String createAccessToken(AuthUser authUser);

  AuthUser readAccessToken(String accessToken);

  /**
   * Access 토큰의 발급 시각을 읽는다 (AU-04 「Access 잔여 TTL 차단」).
   *
   * <p><b>{@link AuthUser} 에 넣지 않는다.</b> 그것은 {@code @AuthenticationPrincipal} 로 남의 컨트롤러가 받는 공개
   * 계약이고, 발급 시각은 토큰의 사정이지 요청자의 속성이 아니다.
   *
   * <p>토큰을 한 번 더 파싱하는 대가를 치른다. 뒤따르는 회원 조회가 SQL 한 번이라 그쪽이 훨씬 무겁고, 대신 {@link #readAccessToken} 의
   * 시그니처가 그대로 남아 이미 쓰는 곳들이 안 바뀐다.
   */
  LocalDateTime readAccessTokenIssuedAt(String accessToken);

  /**
   * Refresh 토큰을 발급한다 (AU-02 Refresh 14일).
   *
   * <p><b>회원번호만 담는다.</b> {@code signupCompleted} 와 {@code admin} 은 14일 안에 바뀌므로(AU-05 가입 완료, 관리자 지정)
   * 재발급 때 DB 에서 다시 읽는다. 토큰에 박아 두면 가입을 마친 사용자가 2주 동안 가입 미완료로 취급된다.
   */
  String createRefreshToken(Long userId);

  /**
   * Refresh 토큰에서 회원번호를 읽는다.
   *
   * <p><b>토큰 자신이 회원번호를 들고 있어야 한다.</b> {@code POST /api/v1/auth/token} 은 등급이 {@code PUBLIC} 이라(API
   * 설계 2-1) Access 토큰이 함께 오지 않고, 재사용 탐지는 이미 행이 지워진 뒤에 「누구 것인가」를 알아야 한다 — AU-03 의 「해당 유저 전체 폐기」가 그
   * 회원번호로 돈다.
   */
  Long readRefreshToken(String refreshToken);
}
