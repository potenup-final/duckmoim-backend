package com.duckmoim.auth.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.exception.AuthErrorCode;
import com.duckmoim.auth.infra.KakaoAuthClient;
import com.duckmoim.common.exception.BusinessException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 카카오 로그인의 <b>HTTP 계약</b>만 본다 — 상태 코드, 응답 필드, 등급 (AU-01).
 *
 * <p>자동 가입과 재방문 판정은 {@code KakaoLoginServiceTest} 가, 카카오와의 요청 두 번은 {@code KakaoAuthClientTest} 가
 * 본다. 여기서 다시 확인하면 같은 규칙이 세 곳에서 관리된다 (테스트 컨벤션 「Controller 테스트에서 비즈니스 로직을 깊게 검증하지 않는다」).
 *
 * <p><b>모든 요청에 토큰을 붙이지 않는다.</b> 이 경로는 {@code PUBLIC} 이고(API 설계 2-1) 로그인 전이라 붙일 토큰이 없다 — 토큰 없이 200 이
 * 나오는 것 자체가 등급 검증이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("카카오 로그인 엔드포인트")
class KakaoLoginControllerTest {

  private static final String PATH = "/api/v1/auth/kakao";
  private static final AtomicLong KAKAO_USER_ID = new AtomicLong(9_500_000);
  private static final String REDIRECT_URI = "http://localhost:3000/auth/kakao/callback";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private KakaoAuthClient kakaoAuthClient;

  @Test
  @DisplayName("인가코드를 보내면 토큰 두 장과 가입 완료 여부가 온다.")
  void loginWithKakao() throws Exception {
    long kakaoUserId = kakaoResponds();

    mockMvc
        .perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("인가코드")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.refreshToken").isNotEmpty())
        .andExpect(jsonPath("$.signupCompleted").value(false))
        .andExpect(jsonPath("$.length()").value(3));

    cleanUp(kakaoUserId);
  }

  @Test
  @DisplayName("인가코드가 비어 있으면 400 이다.")
  void loginWithKakao_blankCode() throws Exception {
    mockMvc
        .perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("")))
        .andExpect(status().isBadRequest());
  }

  /** 프론트가 인가 때 쓴 주소를 그대로 넘겨야 카카오가 교환을 허용한다. 빠지면 카카오까지 왕복할 이유가 없다. */
  @Test
  @DisplayName("리다이렉트 주소가 비어 있으면 400 이다.")
  void loginWithKakao_blankRedirectUri() throws Exception {
    mockMvc
        .perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("인가코드", "")))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("리다이렉트 주소가 주소 형식이 아니면 400 이다.")
  void loginWithKakao_malformedRedirectUri() throws Exception {
    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("인가코드", "duckmoim.com")))
        .andExpect(status().isBadRequest());
  }

  /** 카카오가 거절한 것은 자격증명 실패다. 400 으로 내려보내면 클라이언트가 「입력을 고치라」로 읽는다. */
  @Test
  @DisplayName("카카오가 인가코드를 거절하면 401 이다.")
  void loginWithKakao_rejectedByKakao() throws Exception {
    willThrow(new BusinessException(AuthErrorCode.AUTH_KAKAO_CODE_INVALID))
        .given(kakaoAuthClient)
        .readKakaoUserId(any(), any());

    mockMvc
        .perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("이미쓴코드")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(AuthErrorCode.AUTH_KAKAO_CODE_INVALID.name()));
  }

  private long kakaoResponds() {
    long kakaoUserId = KAKAO_USER_ID.getAndIncrement();
    given(kakaoAuthClient.readKakaoUserId(any(), any())).willReturn(kakaoUserId);
    return kakaoUserId;
  }

  private String body(String code) {
    return body(code, REDIRECT_URI);
  }

  private String body(String code, String redirectUri) {
    return "{\"code\":\"" + code + "\",\"redirectUri\":\"" + redirectUri + "\"}";
  }

  private void cleanUp(long kakaoUserId) {
    jdbcTemplate.update(
        "DELETE FROM refresh_token WHERE user_id IN (SELECT id FROM user WHERE kakao_user_id = ?)",
        kakaoUserId);
    jdbcTemplate.update("DELETE FROM user WHERE kakao_user_id = ?", kakaoUserId);
  }
}
