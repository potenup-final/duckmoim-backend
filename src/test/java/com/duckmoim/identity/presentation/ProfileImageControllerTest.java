package com.duckmoim.identity.presentation;

import static com.duckmoim.identity.UserFixture.aUser;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.identity.domain.ProfileImageStorage;
import com.duckmoim.identity.domain.UploadedImage;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 프로필 이미지 업로드의 <b>HTTP 계약</b>과 검증 기준 (AU-08).
 *
 * <p>형식·크기 판정은 {@code ProfileImagePolicyTest} 가, 키 규칙과 확정의 세 판정은 {@code ProfileImageServiceTest} 가
 * 본다. 여기서 보는 것은 <b>응답 모양 · 등급 · 그리고 「공개 프로필에 반영」</b>이다.
 *
 * <p>마지막 것이 이 티켓의 검증 기준이라 <b>진짜 DB 로 두 엔드포인트를 이어서 부른다.</b> 저장소만 목이다 — 진짜 S3 는 자격증명과 버킷이 필요하고, 검증
 * 하려는 것이 저장소가 아니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("프로필 이미지 엔드포인트")
class ProfileImageControllerTest {

  private static final String PATH = "/api/v1/users/me/profile-image";

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private ProfileImageStorage storage;

  @BeforeEach
  void stubStorage() {
    given(storage.presignUpload(anyString(), anyString()))
        .willAnswer(invocation -> "https://signed.example/" + invocation.getArgument(0));
    given(storage.publicUrlOf(anyString()))
        .willAnswer(invocation -> "https://cdn.example/" + invocation.getArgument(0));
    given(storage.findUploaded(anyString()))
        .willReturn(Optional.of(new UploadedImage("image/webp", 1024)));
  }

  @Test
  @DisplayName("발급하면 업로드 주소와 객체 키와 만료가 온다.")
  void issueUpload() throws Exception {
    long userId = aUser().insert(jdbcTemplate);

    mockMvc
        .perform(
            post(PATH)
                .headers(bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"image/webp\",\"contentLength\":1024}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.uploadUrl").isNotEmpty())
        .andExpect(
            jsonPath("$.objectKey")
                .value(org.hamcrest.Matchers.startsWith("profile/" + userId + "/")))
        .andExpect(jsonPath("$.expiresInSeconds").isNumber())
        .andExpect(jsonPath("$.length()").value(3));

    cleanUp(userId);
  }

  /** 검증 기준의 「MIME 위반 400」이다. */
  @Test
  @DisplayName("허용되지 않은 형식으로 발급하면 400 이다.")
  void issueUpload_disallowedType() throws Exception {
    long userId = aUser().insert(jdbcTemplate);

    mockMvc
        .perform(
            post(PATH)
                .headers(bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"image/gif\",\"contentLength\":1024}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("USER_PROFILE_IMAGE_TYPE_NOT_ALLOWED"));

    cleanUp(userId);
  }

  /** 검증 기준의 「용량 위반 400」이다. */
  @Test
  @DisplayName("상한을 넘는 크기로 발급하면 400 이다.")
  void issueUpload_tooLarge() throws Exception {
    long userId = aUser().insert(jdbcTemplate);

    mockMvc
        .perform(
            post(PATH)
                .headers(bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"image/webp\",\"contentLength\":52428800}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("USER_PROFILE_IMAGE_TOO_LARGE"));

    cleanUp(userId);
  }

  @Test
  @DisplayName("형식을 주지 않으면 400 이다.")
  void issueUpload_blankType() throws Exception {
    long userId = aUser().insert(jdbcTemplate);

    mockMvc
        .perform(
            post(PATH)
                .headers(bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"\",\"contentLength\":1024}"))
        .andExpect(status().isBadRequest());

    cleanUp(userId);
  }

  /**
   * <b>AU-08 의 검증 기준 그 자체</b> — 「업로드 후 `profileImageUrl` 이 채워지고 공개 프로필에 반영」.
   *
   * <p>발급 → (브라우저가 S3 로 올리는 단계는 대역이 대신한다) → 확정 → 공개 프로필 조회까지 이어서 부른다.
   */
  @Test
  @DisplayName("확정하면 공개 프로필에 반영된다.")
  void confirm_reflectedInPublicProfile() throws Exception {
    long userId = aUser().nickname("사진올린덕후").insert(jdbcTemplate);
    String objectKey = "profile/" + userId + "/uploaded.webp";

    mockMvc
        .perform(
            put(PATH)
                .headers(bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"objectKey\":\"" + objectKey + "\"}"))
        .andExpect(status().isOk())
        .andExpect(
            result ->
                org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getContentAsString())
                    .isEmpty());

    mockMvc
        .perform(get("/api/v1/users/" + userId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.profileImageUrl").value("https://cdn.example/" + objectKey));

    cleanUp(userId);
  }

  @Test
  @DisplayName("올라간 것이 없으면 확정이 400 이다.")
  void confirm_notUploaded() throws Exception {
    long userId = aUser().insert(jdbcTemplate);
    given(storage.findUploaded(anyString())).willReturn(Optional.empty());

    mockMvc
        .perform(
            put(PATH)
                .headers(bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"objectKey\":\"profile/" + userId + "/missing.webp\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("USER_PROFILE_IMAGE_NOT_UPLOADED"));

    cleanUp(userId);
  }

  @Test
  @DisplayName("객체 키를 주지 않으면 400 이다.")
  void confirm_blankKey() throws Exception {
    long userId = aUser().insert(jdbcTemplate);

    mockMvc
        .perform(
            put(PATH)
                .headers(bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"objectKey\":\"\"}"))
        .andExpect(status().isBadRequest());

    cleanUp(userId);
  }

  @Test
  @DisplayName("토큰 없이 발급하면 401 이다.")
  void issueUpload_withoutToken() throws Exception {
    mockMvc
        .perform(
            post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"image/webp\",\"contentLength\":1024}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("토큰 없이 확정하면 401 이다.")
  void confirm_withoutToken() throws Exception {
    mockMvc
        .perform(
            put(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"objectKey\":\"profile/1/a.webp\"}"))
        .andExpect(status().isUnauthorized());
  }

  private HttpHeaders bearer(long userId) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(new AuthUser(userId, true, false)));
    return headers;
  }

  private void cleanUp(long userId) {
    jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id = ?", userId);
    jdbcTemplate.update("DELETE FROM user WHERE id = ?", userId);
  }
}
