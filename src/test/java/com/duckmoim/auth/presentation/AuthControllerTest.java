package com.duckmoim.auth.presentation;

import static com.duckmoim.identity.UserFixture.aUser;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.service.AuthService;
import com.duckmoim.auth.service.AuthToken;
import com.duckmoim.identity.domain.SignupStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 재발급·로그아웃의 <b>HTTP 계약</b>만 본다 — 상태 코드, 응답 필드, 등급.
 *
 * <p>회전·재사용 탐지·세션 폐기 같은 규칙은 {@code AuthServiceTest} 가 본다. 여기서 다시 확인하면 같은 규칙이 두 곳에서 관리된다 (테스트 컨벤션
 * 「Controller 테스트에서 비즈니스 로직을 깊게 검증하지 않는다」).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("토큰 재발급과 로그아웃")
class AuthControllerTest {

  private static final String PATH = "/api/v1/auth/token";

  @Autowired private MockMvc mockMvc;
  @Autowired private AuthService authService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("리프레시 토큰을 보내면 토큰 두 장과 가입 완료 여부가 온다.")
  void refresh() throws Exception {
    long userId = aUser().insert(jdbcTemplate);
    AuthToken authToken = authService.createTokens(userId);

    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(authToken.refreshToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.refreshToken").isNotEmpty())
        .andExpect(jsonPath("$.signupCompleted").value(true))
        .andExpect(jsonPath("$.length()").value(3));

    cleanUp(userId);
  }

  /** 가입 화면으로 보낼지를 클라이언트가 이 값으로 정한다 (API-설계.md 2-1). 앱을 다시 켰을 때의 로그인이 곧 재발급이다. */
  @Test
  @DisplayName("가입을 마치지 않은 회원이 재발급하면 가입 완료 여부가 거짓으로 온다.")
  void refresh_signupIncomplete() throws Exception {
    long userId = aUser().status(SignupStatus.PENDING_SIGNUP_INFO).insert(jdbcTemplate);
    AuthToken authToken = authService.createTokens(userId);

    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(authToken.refreshToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.signupCompleted").value(false));

    cleanUp(userId);
  }

  @Test
  @DisplayName("리프레시 토큰이 비어 있으면 400 이다.")
  void refresh_blankToken() throws Exception {
    mockMvc
        .perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("")))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("같은 리프레시 토큰을 두 번 쓰면 두 번째는 401 이다.")
  void refresh_reused() throws Exception {
    long userId = aUser().insert(jdbcTemplate);
    AuthToken authToken = authService.createTokens(userId);
    authService.refresh(authToken.refreshToken());

    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(authToken.refreshToken())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_INVALID"));

    cleanUp(userId);
  }

  /** 등급이 {@code PUBLIC} 이다 — Access 가 이미 만료됐을 때 부르는 경로라 Access 를 요구할 수 없다. */
  @Test
  @DisplayName("재발급은 액세스 토큰 없이 부를 수 있다.")
  void refresh_isPublic() throws Exception {
    long userId = aUser().insert(jdbcTemplate);
    AuthToken authToken = authService.createTokens(userId);

    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(authToken.refreshToken())))
        .andExpect(status().isOk());

    cleanUp(userId);
  }

  @Test
  @DisplayName("로그아웃은 본문 없이 200 을 준다.")
  void logout() throws Exception {
    long userId = aUser().insert(jdbcTemplate);
    AuthToken authToken = authService.createTokens(userId);

    mockMvc
        .perform(
            delete(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken.accessToken()))
        .andExpect(status().isOk());

    cleanUp(userId);
  }

  /** 등급이 {@code AUTH} 다. 익명 로그아웃은 지울 대상이 없다. */
  @Test
  @DisplayName("액세스 토큰 없이 로그아웃하면 401 이다.")
  void logout_anonymous() throws Exception {
    mockMvc.perform(delete(PATH)).andExpect(status().isUnauthorized());
  }

  private String body(String refreshToken) {
    return "{\"refreshToken\":\"" + refreshToken + "\"}";
  }

  private void cleanUp(long userId) {
    jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id = ?", userId);
    jdbcTemplate.update("DELETE FROM user WHERE id = ?", userId);
  }
}
