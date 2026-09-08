package com.duckmoim.auth.presentation;

import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.infra.JwtProvider;
import com.duckmoim.auth.service.AuthService;
import com.duckmoim.identity.domain.SignupStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 관문이 <b>어떻게 응답하는가</b>를 본다 — 토큰 상태별 판정, 에러 봉투의 모양, 세션 미발급.
 *
 * <p>경로별 등급은 {@link EndpointGradeTest} 가 위키 표 전체로 검증한다. 여기서 같은 조합을 다시 검사하지 않는다 — 두 곳에서 관리하면 갈라진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("인증·인가 관문의 응답")
class AuthGatewayTest {

  private static final String LOCAL_SECRET =
      "duckmoim-local-development-secret-do-not-use-in-production";
  private static final String OTHER_SECRET = "someone-elses-secret-key-32-bytes-or-longer-here";
  private static final Duration REFRESH_TTL = Duration.ofDays(14);

  /**
   * V11 시드의 1번·2번은 둘 다 {@code ACTIVE} 다.
   *
   * <p><b>가입 미완료 상수를 두지 않는다.</b> 관문이 가입 완료 여부를 토큰이 아니라 회원 행에서 읽게 됐다 (I-02 · AU-07) — 토큰에 {@code
   * false} 를 담아도 DB 가 {@code ACTIVE} 면 통과한다. 미완료를 검증하는 테스트는 <b>회원을 그 상태로 만들어</b> 쓴다.
   */
  private static final AuthUser SIGNUP_COMPLETED = new AuthUser(2L, true, false);

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;
  @Autowired private AuthService authService;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("토큰이 없어도 헬스체크는 200 이다.")
  void healthCheckWithoutToken() throws Exception {
    mockMvc.perform(get("/api/health")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("등급을 선언하지 않은 경로는 401 이다.")
  void undeclaredPath() throws Exception {
    mockMvc.perform(get("/api/v1/not-declared")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("만료된 토큰으로 요청하면 401 이다.")
  void expiredToken() throws Exception {
    String expired =
        new JwtProvider(LOCAL_SECRET, Duration.ofMinutes(-1), REFRESH_TTL)
            .createAccessToken(SIGNUP_COMPLETED);

    mockMvc
        .perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("서명이 위조된 토큰으로 요청하면 401 이다.")
  void forgedToken() throws Exception {
    String forged =
        new JwtProvider(OTHER_SECRET, Duration.ofMinutes(30), REFRESH_TTL)
            .createAccessToken(SIGNUP_COMPLETED);

    mockMvc
        .perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("인증 실패 응답도 code 와 message 두 필드로 나간다.")
  void unauthorizedResponseUsesCommonEnvelope() throws Exception {
    mockMvc
        .perform(delete("/api/v1/auth/token"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_INVALID"))
        .andExpect(jsonPath("$.message").isNotEmpty())
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  @DisplayName("만료된 토큰은 재발급하라는 에러 코드로 구분해서 알려준다.")
  void expiredTokenTellsClientToRefresh() throws Exception {
    String expired =
        new JwtProvider(LOCAL_SECRET, Duration.ofMinutes(-1), REFRESH_TTL)
            .createAccessToken(SIGNUP_COMPLETED);

    mockMvc
        .perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_EXPIRED"));
  }

  @Test
  @DisplayName("가입 미완료로 막히면 가입 정보를 입력하라는 에러 코드가 나간다.")
  void forbiddenBySignupIncomplete() throws Exception {
    long userId = pendingUser();

    mockMvc
        .perform(post("/api/v1/posts").headers(bearer(new AuthUser(userId, false, false))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("USER_SIGNUP_INFO_REQUIRED"));

    cleanUp(userId);
  }

  @Test
  @DisplayName("관리자가 아니라서 막히면 권한 없음 에러 코드가 나간다.")
  void forbiddenByNotAdmin() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/reports").headers(bearer(SIGNUP_COMPLETED)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
  }

  /** 가입을 마쳐도 관리자가 되지 않으므로, 여기서 「가입 정보를 입력하세요」가 나가면 사용자는 가입을 마치고 다시 막힌다. 빠져나올 수 없는 안내다. */
  @Test
  @DisplayName("가입 미완료 계정이 관리자 경로에서 막혀도 가입 안내가 아니라 권한 없음이 나간다.")
  void forbiddenByNotAdmin_signupIncomplete() throws Exception {
    long pending = pendingUser();

    mockMvc
        .perform(get("/api/v1/admin/reports").headers(bearer(new AuthUser(pending, false, false))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

    cleanUp(pending);
  }

  @Test
  @DisplayName("인증 요청에 JSESSIONID 쿠키가 발급되지 않는다.")
  void noSessionCookie() throws Exception {
    var response =
        mockMvc
            .perform(get("/api/v1/users/me").headers(bearer(SIGNUP_COMPLETED)))
            .andReturn()
            .getResponse();

    assertThat(response.getCookie("JSESSIONID")).isNull();
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
  }

  /**
   * AU-04 의 검증 기준 그 자체 — 「로그아웃 직후 기존 Access 로 401」.
   *
   * <p><b>서명만 보면 이 토큰은 멀쩡하다.</b> 만료도 안 됐고 위조도 아니다. 그래서 이 한 줄이 초록불이려면 관문이 서명 너머로 <b>회원의 무효화 시각</b>까지
   * 봐야 한다. 관문을 DB 판정으로 바꾼 이유가 여기 있다.
   */
  @Test
  @DisplayName("로그아웃하면 그전에 발급된 액세스 토큰으로는 401 이다.")
  void logoutInvalidatesIssuedAccessToken() throws Exception {
    long userId = aUser().insert(jdbcTemplate);
    String accessToken = authService.createTokens(userId).accessToken();

    authService.logout(userId);

    mockMvc
        .perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_INVALID"));

    cleanUp(userId);
  }

  /**
   * 무효화 뒤에 받은 토큰은 살아 있어야 한다. 아니면 로그아웃한 사람이 다시 못 들어온다.
   *
   * <p><b>무효화 시각을 픽스처로 과거에 박는다.</b> {@code logout()} 을 부르고 바로 발급하면 <b>같은 초</b>가 되는데, JWT 의 {@code
   * iat} 은 초 단위로 내려가므로 그 토큰이 거절된다 — 실측했다. 프로덕션에서는 로그아웃과 재로그인이 다른 요청이라 생기지 않는 일이고, 여기서 보려는 것은 시계가
   * 아니라 <b>「나중에 발급된 토큰은 통과한다」</b>이다.
   */
  @Test
  @DisplayName("무효화 시각보다 나중에 발급된 액세스 토큰은 관문을 통과한다.")
  void tokenIssuedAfterInvalidationIsAccepted() throws Exception {
    long userId =
        aUser()
            .tokensInvalidatedAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(1))
            .insert(jdbcTemplate);
    String accessToken = authService.createTokens(userId).accessToken();

    int status =
        mockMvc
            .perform(
                get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
            .andReturn()
            .getResponse()
            .getStatus();

    assertThat(status).isNotEqualTo(401);
    cleanUp(userId);
  }

  private long pendingUser() {
    return aUser().status(SignupStatus.PENDING_SIGNUP_INFO).insert(jdbcTemplate);
  }

  private void cleanUp(long userId) {
    jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id = ?", userId);
    jdbcTemplate.update("DELETE FROM user WHERE id = ?", userId);
  }

  private HttpHeaders bearer(AuthUser authUser) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(authUser));
    return headers;
  }
}
