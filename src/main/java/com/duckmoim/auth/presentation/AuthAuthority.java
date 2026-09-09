package com.duckmoim.auth.presentation;

public final class AuthAuthority {

  public static final String SIGNUP = "SIGNUP";
  public static final String ADMIN = "ADMIN";

  /**
   * 크롤러 전용 (API-설계 「1. 권한 등급」 · D-11).
   *
   * <p><b>앞의 둘과 계보가 다르다.</b> 저것들은 카카오 JWT 를 통과한 사람에게 붙는데 이것은 정적 키로 붙는다 — GitHub Actions 러너에는 사람이 없어
   * 카카오 로그인을 할 수 없고, access 토큰 TTL 이 30분이라 시크릿에 토큰을 넣어 두면 다음 날 잡에서 만료된다.
   */
  public static final String MACHINE = "MACHINE";

  private AuthAuthority() {}
}
