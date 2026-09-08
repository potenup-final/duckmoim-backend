package com.duckmoim.identity.presentation;

import static com.duckmoim.identity.UserFixture.aUser;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
@DisplayName("가입 정보 · 내 정보 · 프로필")
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

  /** AU-08 의 응답 계약 — 본문 없이 200 이다. 컨벤션 표에 204 가 없다. */
  @Test
  @DisplayName("프로필을 수정하면 본문 없이 200 이다.")
  void updateProfile() throws Exception {
    long userId = aUser().nickname("계약전덕후").insert(jdbcTemplate);

    mockMvc
        .perform(
            patch("/api/v1/users/me/profile")
                .headers(bearer(userId, true))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"계약후덕후\",\"bio\":\"새 소개\"}"))
        .andExpect(status().isOk())
        .andExpect(
            result ->
                org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getContentAsString())
                    .isEmpty());

    cleanUp(userId);
  }

  /** {@code @Size(min = 1)} 이 {@code null} 은 통과시키고 빈 문자열은 막는다 — 닉네임은 비울 수 없다. */
  @Test
  @DisplayName("닉네임을 빈 문자열로 보내면 400 이다.")
  void updateProfile_blankNickname() throws Exception {
    long userId = aUser().insert(jdbcTemplate);

    mockMvc
        .perform(
            patch("/api/v1/users/me/profile")
                .headers(bearer(userId, true))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"\"}"))
        .andExpect(status().isBadRequest());

    cleanUp(userId);
  }

  @Test
  @DisplayName("한줄소개가 100자를 넘으면 400 이다.")
  void updateProfile_bioTooLong() throws Exception {
    long userId = aUser().insert(jdbcTemplate);

    mockMvc
        .perform(
            patch("/api/v1/users/me/profile")
                .headers(bearer(userId, true))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bio\":\"" + "가".repeat(101) + "\"}"))
        .andExpect(status().isBadRequest());

    cleanUp(userId);
  }

  /**
   * 출생연도를 <b>거부하지 않고 무시한다.</b> 요청 DTO 에 필드가 없어서 잭슨이 흘려보낸다 — 400 을 내면 그 필드가 언젠가 열릴 것처럼 보인다 (API 설계
   * 2-2 「출생연도는 받지 않는다」).
   */
  @Test
  @DisplayName("출생연도를 함께 보내도 200 이고 값이 바뀌지 않는다.")
  void updateProfile_ignoresBirthYear() throws Exception {
    long userId = aUser().nickname("연도보낸덕후").insert(jdbcTemplate);

    mockMvc
        .perform(
            patch("/api/v1/users/me/profile")
                .headers(bearer(userId, true))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"연도무시덕후\",\"birthYear\":1900}"))
        .andExpect(status().isOk());

    Integer birthYear =
        jdbcTemplate.queryForObject(
            "SELECT birth_year FROM user WHERE id = ?", Integer.class, userId);
    org.assertj.core.api.Assertions.assertThat(birthYear).isEqualTo(1998);

    cleanUp(userId);
  }

  @Test
  @DisplayName("토큰 없이 프로필을 수정하면 401 이다.")
  void updateProfile_withoutToken() throws Exception {
    mockMvc
        .perform(
            patch("/api/v1/users/me/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"익명덕후\"}"))
        .andExpect(status().isUnauthorized());
  }

  /** AU-09 의 노출 범위다. <b>필드가 다섯을 넘으면 실패한다</b> — 출생연도나 가입 상태가 새어 나가면 여기서 잡힌다. */
  @Test
  @DisplayName("공개 프로필에는 노출 항목 넷과 회원번호만 나간다.")
  void findPublicProfile() throws Exception {
    long userId =
        aUser().nickname("공개프로필덕후").profile("생카 돌기 좋아해요", "/avatar/a1.webp").insert(jdbcTemplate);

    mockMvc
        .perform(get("/api/v1/users/" + userId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(userId))
        .andExpect(jsonPath("$.nickname").value("공개프로필덕후"))
        .andExpect(jsonPath("$.bio").value("생카 돌기 좋아해요"))
        .andExpect(jsonPath("$.profileImageUrl").value("/avatar/a1.webp"))
        // 접속이 관측된 적 없는 픽스처라 값이 null 이다. 키는 있다 (API 컨벤션 「DTO 규칙」)
        .andExpect(jsonPath("$.lastSeen").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.birthYear").doesNotExist())
        .andExpect(jsonPath("$.signupCompleted").doesNotExist())
        .andExpect(jsonPath("$.sanction").doesNotExist())
        .andExpect(jsonPath("$.length()").value(5));

    cleanUp(userId);
  }

  /** 프론트가 이 문자열을 화면 문구로 매핑한다 (화면 계약 3장의 표). 열거값 이름이 그대로 나가야 한다. */
  @Test
  @DisplayName("최근 접속은 구간 이름 문자열로 나간다.")
  void findPublicProfile_lastSeen() throws Exception {
    long userId =
        aUser()
            .nickname("접속있는덕후")
            .lastSeenAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(5))
            .insert(jdbcTemplate);

    mockMvc
        .perform(get("/api/v1/users/" + userId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lastSeen").value("WITHIN_WEEK"));

    cleanUp(userId);
  }

  /** 만나기 전에 상대를 확인하는 화면이라 로그인을 요구하면 그 확인이 막힌다 (등급 {@code PUBLIC}). */
  @Test
  @DisplayName("공개 프로필은 토큰 없이 읽을 수 있다.")
  void findPublicProfile_withoutToken() throws Exception {
    long userId = aUser().nickname("비회원도보는덕후").insert(jdbcTemplate);

    mockMvc.perform(get("/api/v1/users/" + userId)).andExpect(status().isOk());

    cleanUp(userId);
  }

  @Test
  @DisplayName("탈퇴한 회원의 공개 프로필은 404 다.")
  void findPublicProfile_withdrawn() throws Exception {
    long userId = aUser().status(SignupStatus.WITHDRAWN).insert(jdbcTemplate);

    mockMvc.perform(get("/api/v1/users/" + userId)).andExpect(status().isNotFound());

    cleanUp(userId);
  }

  @Test
  @DisplayName("가입을 마치지 않은 회원의 공개 프로필은 404 다.")
  void findPublicProfile_signupIncomplete() throws Exception {
    long userId = aUser().status(SignupStatus.PENDING_SIGNUP_INFO).insert(jdbcTemplate);

    mockMvc.perform(get("/api/v1/users/" + userId)).andExpect(status().isNotFound());

    cleanUp(userId);
  }

  /**
   * <b>{@code /users/me} 가 {@code /users/&#123;userId&#125;} 에 먹히지 않는지 본다.</b>
   *
   * <p>둘이 같은 모양이고 등급도 다르다 ({@code AUTH} vs {@code PUBLIC}). 스프링이 리터럴 경로를 변수 경로보다 먼저 고르므로 지금은 내 정보로
   * 가는데, 그 우선순위가 뒤집히면 {@code "me"} 를 {@code Long} 으로 바꾸다 터진다. <b>조용히 뒤집히지 않게 못박는다.</b>
   */
  @Test
  @DisplayName("내 정보 경로는 회원번호 경로에 먹히지 않는다.")
  void findMyProfile_isNotSwallowedByPathVariable() throws Exception {
    long userId = aUser().nickname("리터럴우선덕후").insert(jdbcTemplate);

    mockMvc
        .perform(get("/api/v1/users/me").headers(bearer(userId, true)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.signupCompleted").value(true))
        .andExpect(jsonPath("$.sanction").exists());

    cleanUp(userId);
  }

  /** 같은 자리에 있는 또 하나의 리터럴 경로다. 이쪽이 먹히면 닉네임 사전 조회가 죽는다. */
  @Test
  @DisplayName("닉네임 확인 경로도 회원번호 경로에 먹히지 않는다.")
  void checkNicknameAvailability_isNotSwallowedByPathVariable() throws Exception {
    long userId = aUser().insert(jdbcTemplate);

    mockMvc
        .perform(
            get("/api/v1/users/nickname-availability")
                .param("nickname", "먹히지않는이름")
                .headers(bearer(userId, false)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").exists());

    cleanUp(userId);
  }

  /** 숫자가 아닌 값은 타입 변환에서 걸린다. {@code GlobalExceptionHandler} 가 400 으로 옮긴다. */
  @Test
  @DisplayName("회원번호가 숫자가 아니면 400 이다.")
  void findPublicProfile_malformedUserId() throws Exception {
    mockMvc.perform(get("/api/v1/users/abc")).andExpect(status().isBadRequest());
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
