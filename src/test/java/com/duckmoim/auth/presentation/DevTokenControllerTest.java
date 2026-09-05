package com.duckmoim.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.infra.JwtProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DevTokenController.class)
@ActiveProfiles("local")
@Import({
  SecurityConfig.class,
  RestAuthenticationEntryPoint.class,
  RestAccessDeniedHandler.class,
  JwtProvider.class
})
@DisplayName("개발용 토큰 발급")
class DevTokenControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private TokenProvider tokenProvider;

  @Test
  @DisplayName("토큰이 없어도 개발용 토큰을 받을 수 있다.")
  void issuesWithoutToken() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/dev/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(new DevTokenRequest(7L, true, true))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty());
  }

  @Test
  @DisplayName("발급한 개발용 토큰은 요청한 회원과 등급을 그대로 담는다.")
  void issuedTokenCarriesRequestedUser() throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/v1/dev/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(new DevTokenRequest(7L, true, true))))
            .andReturn()
            .getResponse()
            .getContentAsString();

    String accessToken = objectMapper.readValue(response, DevTokenResponse.class).accessToken();

    assertThat(tokenProvider.readAccessToken(accessToken)).isEqualTo(new AuthUser(7L, true, true));
  }

  @Test
  @DisplayName("회원번호 없이 요청하면 400 이다.")
  void issue_userIdIsNull() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/dev/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(new DevTokenRequest(null, true, false))))
        .andExpect(status().isBadRequest());
  }

  private String body(DevTokenRequest request) throws Exception {
    return objectMapper.writeValueAsString(request);
  }
}
