package com.duckmoim.auth.service;

/**
 * 발급된 토큰 두 장과 가입 완료 여부 (AU-02 · AU-03).
 *
 * <p><b>{@code signupCompleted} 를 함께 준다.</b> API-설계.md 2-1 이 <i>"로그인 응답에 signupCompleted 를 담는다.
 * 클라이언트가 가입 화면으로 보낼지 판단하는 유일한 근거다"</i> 라고 정했는데, <b>앱을 다시 켰을 때의 로그인은 재발급이다</b> — 손에 남아 있는 것이 Refresh
 * 뿐이라 {@code POST /auth/token} 으로 시작한다. 그래서 재발급 응답에도 있어야 한다.
 *
 * <p>없으면 클라이언트가 Access 토큰의 페이로드를 직접 뜯어 봐야 한다. 토큰의 내부 구조가 클라이언트 계약이 되어버린다.
 *
 * <p>{@code domain} 이 아니라 여기 있는 이유 — 두 토큰을 묶는 것은 도메인 규칙이 아니라 <b>유스케이스의 결과</b>다 ({@code EventSlice}
 * · {@code WrittenComment} 와 같은 자리).
 */
public record AuthToken(String accessToken, String refreshToken, boolean signupCompleted) {}
