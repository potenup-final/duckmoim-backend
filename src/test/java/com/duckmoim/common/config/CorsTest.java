package com.duckmoim.common.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("브라우저가 다른 도메인에서 API 를 부를 수 있다")
class CorsTest {

  private static final String FRONTEND = "http://localhost:3000";

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("허용된 출처의 사전 요청은 토큰이 없어도 통과한다.")
  void preflightFromAllowedOrigin() throws Exception {
    mockMvc
        .perform(
            options("/api/v1/users/me")
                .header(HttpHeaders.ORIGIN, FRONTEND)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.AUTHORIZATION))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND));
  }

  @Test
  @DisplayName("허용하지 않은 출처의 사전 요청은 거절한다.")
  void preflightFromUnknownOrigin() throws Exception {
    mockMvc
        .perform(
            options("/api/v1/users/me")
                .header(HttpHeaders.ORIGIN, "https://attacker.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("쿠키를 쓰지 않으므로 자격증명 허용을 열지 않는다.")
  void doesNotAllowCredentials() throws Exception {
    mockMvc
        .perform(
            options("/api/v1/users/me")
                .header(HttpHeaders.ORIGIN, FRONTEND)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
        .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
  }
}
