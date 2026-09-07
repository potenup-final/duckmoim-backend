package com.duckmoim.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.infra.JwtProvider;
import java.time.Duration;
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

  private static final AuthUser SIGNUP_INCOMPLETE = new AuthUser(1L, false, false);
  private static final AuthUser SIGNUP_COMPLETED = new AuthUser(2L, true, false);

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

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
        new JwtProvider(LOCAL_SECRET, Duration.ofMinutes(-1)).issueAccessToken(SIGNUP_COMPLETED);

    mockMvc
        .perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("서명이 위조된 토큰으로 요청하면 401 이다.")
  void forgedToken() throws Exception {
    String forged =
        new JwtProvider(OTHER_SECRET, Duration.ofMinutes(30)).issueAccessToken(SIGNUP_COMPLETED);

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
        new JwtProvider(LOCAL_SECRET, Duration.ofMinutes(-1)).issueAccessToken(SIGNUP_COMPLETED);

    mockMvc
        .perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_ACCESS_TOKEN_EXPIRED"));
  }

  @Test
  @DisplayName("가입 미완료로 막히면 가입 정보를 입력하라는 에러 코드가 나간다.")
  void forbiddenBySignupIncomplete() throws Exception {
    mockMvc
        .perform(post("/api/v1/posts").headers(bearer(SIGNUP_INCOMPLETE)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("USER_SIGNUP_INFO_REQUIRED"));
  }

  @Test
  @DisplayName("관리자가 아니라서 막히면 권한 없음 에러 코드가 나간다.")
  void forbiddenByNotAdmin() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/reports").headers(bearer(SIGNUP_COMPLETED)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
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

  private HttpHeaders bearer(AuthUser authUser) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.issueAccessToken(authUser));
    return headers;
  }
}
