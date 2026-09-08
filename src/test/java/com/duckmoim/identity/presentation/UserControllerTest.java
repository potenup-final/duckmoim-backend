package com.duckmoim.identity.presentation;

import static com.duckmoim.identity.UserFixture.aUser;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
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
 * 가입 정보 입력과 내 정보의 <b>HTTP 계약</b>만 본다 — 상태 코드, 응답 필드, 등급.
 *
 * <p>만 14세 판정 · 닉네임 중복 · 동시 요청은 {@code UserServiceTest} 가 본다. 여기서 다시 확인하면 같은 규칙이 두 곳에서 관리된다 (테스트
 * 컨벤션 「Controller 테스트에서 비즈니스 로직을 깊게 검증하지 않는다」).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("가입 정보와 내 정보")
class UserControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("쓰이지 않는 닉네임은 쓸 수 있다고 답한다.")
  void checkNicknameAvailability() throws Exception {
    long userId = aUser().insert(jdbcTemplate);

    mockMvc
        .perform(
            get("/api/v1/users/nickname-availability")
                .param("nickname", "아직없는이름")
                .headers(bearer(userId, false)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(true))
        .andExpect(jsonPath("$.length()").value(1));

    cleanUp(userId);
  }

  @Test
  @DisplayName("닉네임을 주지 않으면 400 이다.")
  void checkNicknameAvailability_blank() throws Exception {
    long userId = aUser().insert(jdbcTemplate);

    mockMvc
        .perform(
            get("/api/v1/users/nickname-availability")
                .param("nickname", " ")
                .headers(bearer(userId, false)))
        .andExpect(status().isBadRequest());

    cleanUp(userId);
  }

  /** 가입 미완료 사용자가 부르는 경로다 — 등급이 {@code AUTH} 라 가입 여부를 묻지 않는다. */
  @Test
  @DisplayName("가입 미완료 계정도 닉네임을 확인할 수 있다.")
  void checkNicknameAvailability_signupIncomplete() throws Exception {
    long userId = pendingUser();

    mockMvc
        .perform(
            get("/api/v1/users/nickname-availability")
                .param("nickname", "고민중인이름")
                .headers(bearer(userId, false)))
        .andExpect(status().isOk());

    cleanUp(userId);
  }

  @Test
  @DisplayName("가입 정보를 입력하면 본문 없이 200 이다.")
  void completeSignup() throws Exception {
    long userId = pendingUser();

    mockMvc
        .perform(
            put("/api/v1/users/me/signup-info")
                .headers(bearer(userId, false))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"성수출입증\",\"birthYear\":2000}"))
        .andExpect(status().isOk());

    cleanUp(userId);
  }

  @Test
  @DisplayName("닉네임이 20자를 넘으면 400 이다.")
  void completeSignup_nicknameTooLong() throws Exception {
    long userId = pendingUser();

    mockMvc
        .perform(
            put("/api/v1/users/me/signup-info")
                .headers(bearer(userId, false))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + "가".repeat(21) + "\",\"birthYear\":2000}"))
        .andExpect(status().isBadRequest());

    cleanUp(userId);
  }

  @Test
  @DisplayName("출생연도를 주지 않으면 400 이다.")
  void completeSignup_birthYearMissing() throws Exception {
    long userId = pendingUser();

    mockMvc
        .perform(
            put("/api/v1/users/me/signup-info")
                .headers(bearer(userId, false))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"출생연도없음\"}"))
        .andExpect(status().isBadRequest());

    cleanUp(userId);
  }

  @Test
  @DisplayName("가입을 마친 계정이 다시 입력하면 409 다.")
  void completeSignup_alreadySet() throws Exception {
    long userId = aUser().nickname("이미가입완료").insert(jdbcTemplate);

    mockMvc
        .perform(
            put("/api/v1/users/me/signup-info")
                .headers(bearer(userId, true))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"바꿔보려는이름\",\"birthYear\":2000}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("USER_SIGNUP_INFO_ALREADY_SET"));

    cleanUp(userId);
  }

  /** 정본이 담기는 것을 넷으로 못박았다 — 프로필 · 가입 여부 · 최근 접속 구간 · 제재. */
  @Test
  @DisplayName("내 정보를 읽으면 프로필과 가입 여부와 제재 상태가 온다.")
  void findMyProfile() throws Exception {
    long userId = aUser().nickname("내정보주인").insert(jdbcTemplate);

    mockMvc
        .perform(get("/api/v1/users/me").headers(bearer(userId, true)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(userId))
        .andExpect(jsonPath("$.nickname").value("내정보주인"))
        .andExpect(jsonPath("$.signupCompleted").value(true))
        .andExpect(jsonPath("$.sanction.kind").value("NONE"))
        .andExpect(jsonPath("$.length()").value(7));

    cleanUp(userId);
  }

  /** null 이 될 수 있는 필드를 생략하지 않고 null 로 명시한다 (API 컨벤션). 키가 없으면 프론트가 분기를 더 만든다. */
  @Test
  @DisplayName("가입 미완료 계정의 내 정보는 닉네임 키가 null 로 나간다.")
  void findMyProfile_signupIncomplete() throws Exception {
    long userId = pendingUser();

    mockMvc
        .perform(get("/api/v1/users/me").headers(bearer(userId, false)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.signupCompleted").value(false))
        .andExpect(jsonPath("$.nickname").isEmpty())
        .andExpect(jsonPath("$").value(org.hamcrest.Matchers.hasKey("nickname")));

    cleanUp(userId);
  }

  @Test
  @DisplayName("토큰 없이 내 정보를 읽으면 401 이다.")
  void findMyProfile_anonymous() throws Exception {
    mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
  }

  private long pendingUser() {
    return aUser().status(SignupStatus.PENDING_SIGNUP_INFO).insert(jdbcTemplate);
  }

  private HttpHeaders bearer(long userId, boolean signupCompleted) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(
        tokenProvider.createAccessToken(new AuthUser(userId, signupCompleted, false)));
    return headers;
  }

  private void cleanUp(long userId) {
    jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id = ?", userId);
    jdbcTemplate.update("DELETE FROM user WHERE id = ?", userId);
  }
}
