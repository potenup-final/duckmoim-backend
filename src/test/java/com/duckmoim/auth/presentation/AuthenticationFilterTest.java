package com.duckmoim.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.exception.AuthErrorCode;
import com.duckmoim.auth.infra.JwtProvider;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class AuthenticationFilterTest {

  private static final String SECRET = "duckmoim-unit-test-secret-key-32-bytes-or-longer";

  private final JwtProvider jwtProvider = new JwtProvider(SECRET, Duration.ofMinutes(30));
  private final AuthenticationFilter filter = new AuthenticationFilter(jwtProvider);

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("토큰이 없는 요청은 익명인 채로 통과한다.")
  void doFilter_withoutToken() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(request.getAttribute(AuthenticationFilter.ERROR_CODE)).isNull();
  }

  @Test
  @DisplayName("유효한 토큰을 보내면 인증 주체가 토큰의 회원으로 채워진다.")
  void doFilter_withValidToken() throws Exception {
    AuthUser authUser = new AuthUser(7L, true, false);
    MockHttpServletRequest request = requestWith(jwtProvider.issueAccessToken(authUser));

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    assertThat(authentication.getPrincipal()).isEqualTo(authUser);
  }

  @Test
  @DisplayName("가입을 마친 회원에게는 SIGNUP 권한이 주어진다.")
  void doFilter_signupCompleted() throws Exception {
    MockHttpServletRequest request =
        requestWith(jwtProvider.issueAccessToken(new AuthUser(7L, true, false)));

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
        .extracting("authority")
        .containsExactly(AuthAuthority.SIGNUP);
  }

  @Test
  @DisplayName("가입 미완료 회원에게는 아무 권한도 주어지지 않는다.")
  void doFilter_signupIncomplete() throws Exception {
    MockHttpServletRequest request =
        requestWith(jwtProvider.issueAccessToken(new AuthUser(7L, false, false)));

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities()).isEmpty();
  }

  @Test
  @DisplayName("관리자에게는 SIGNUP 과 ADMIN 권한이 함께 주어진다.")
  void doFilter_admin() throws Exception {
    MockHttpServletRequest request =
        requestWith(jwtProvider.issueAccessToken(new AuthUser(7L, true, true)));

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
        .extracting("authority")
        .containsExactlyInAnyOrder(AuthAuthority.SIGNUP, AuthAuthority.ADMIN);
  }

  @Test
  @DisplayName("만료된 토큰을 보내면 인증되지 않고 만료 사유가 요청에 남는다.")
  void doFilter_expiredToken() throws Exception {
    JwtProvider alreadyExpired = new JwtProvider(SECRET, Duration.ofMinutes(-1));
    MockHttpServletRequest request =
        requestWith(alreadyExpired.issueAccessToken(new AuthUser(7L, true, false)));

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(request.getAttribute(AuthenticationFilter.ERROR_CODE))
        .isEqualTo(AuthErrorCode.AUTH_ACCESS_TOKEN_EXPIRED);
  }

  @Test
  @DisplayName("서명이 위조된 토큰을 보내면 인증되지 않고 위조 사유가 요청에 남는다.")
  void doFilter_forgedToken() throws Exception {
    JwtProvider forger =
        new JwtProvider("someone-elses-secret-key-32-bytes-or-longer-here", Duration.ofMinutes(30));
    MockHttpServletRequest request =
        requestWith(forger.issueAccessToken(new AuthUser(7L, true, true)));

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(request.getAttribute(AuthenticationFilter.ERROR_CODE))
        .isEqualTo(AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID);
  }

  @Test
  @DisplayName("Bearer 접두어가 없는 헤더는 토큰으로 보지 않는다.")
  void doFilter_headerWithoutBearerPrefix() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", jwtProvider.issueAccessToken(new AuthUser(7L, true, false)));

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(request.getAttribute(AuthenticationFilter.ERROR_CODE)).isNull();
  }

  private MockHttpServletRequest requestWith(String accessToken) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + accessToken);
    return request;
  }
}
