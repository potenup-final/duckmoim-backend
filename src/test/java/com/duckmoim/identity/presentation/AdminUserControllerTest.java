package com.duckmoim.identity.presentation;

import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.presentation.ImportSecurity;
import com.duckmoim.identity.service.AdminUserPurgeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 계정 파기의 HTTP 계약 (AD-05).
 *
 * <p>파기가 무엇을 비우는지와 감사 로그가 함께 남는지는 {@code AdminUserPurgeServiceTest} 가 본다. 여기서는 <b>경로와 본문이 서비스 인자로
 * 옮겨지는지</b>와 사유 검증만 본다.
 *
 * <p>등급 판정(익명 401 · 일반 계정 403)은 여기 없다 — {@code EndpointGradeTest} 의 권한 표가 이 경로 행으로 이미 지킨다.
 */
@WebMvcTest(AdminUserController.class)
@ImportSecurity
class AdminUserControllerTest {

  private static final long ADMIN_ID = 6L;
  private static final long TARGET_ID = 41L;
  private static final String REASON = "나이 확인 요청에 30일간 답이 없었습니다";

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private AdminUserPurgeService adminUserPurgeService;

  /** 행위자는 경로가 아니라 토큰에서 온다. 감사 로그가 「누가 했나」를 그 값으로 적는다. */
  @DisplayName("계정을 파기하면 대상과 행위자와 사유가 그대로 넘어간다.")
  @Test
  void purge() throws Exception {
    mockMvc
        .perform(
            purgeRequest(
                """
            {"reason": "%s"}
            """
                    .formatted(REASON)))
        .andExpect(status().isOk());

    then(adminUserPurgeService).should().purge(TARGET_ID, ADMIN_ID, REASON);
  }

  /** 사유는 감사 로그에 남는 유일한 설명이다. 비어 있으면 왜 파기했는지를 되짚을 자리가 없어진다. */
  @DisplayName("사유가 비어 있으면 파기할 수 없다.")
  @Test
  void purge_blankReason() throws Exception {
    mockMvc
        .perform(
            purgeRequest(
                """
        {"reason": "  "}
        """))
        .andExpect(status().isBadRequest());

    then(adminUserPurgeService).shouldHaveNoInteractions();
  }

  /** {@code audit_log.detail} 이 500 자다 (V36). 저장 시점에 걸리면 고칠 수 없는 기록이라 늦다. */
  @DisplayName("사유가 500 자를 넘으면 파기할 수 없다.")
  @Test
  void purge_reasonTooLong() throws Exception {
    mockMvc
        .perform(
            purgeRequest(
                """
            {"reason": "%s"}
            """
                    .formatted("가".repeat(501))))
        .andExpect(status().isBadRequest());

    then(adminUserPurgeService).shouldHaveNoInteractions();
  }

  @DisplayName("사유 없이 부르면 파기할 수 없다.")
  @Test
  void purge_missingReason() throws Exception {
    mockMvc.perform(purgeRequest("{}")).andExpect(status().isBadRequest());

    then(adminUserPurgeService).shouldHaveNoInteractions();
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder purgeRequest(
      String body) {
    return post("/api/v1/admin/users/{userId}/purge", TARGET_ID)
        .headers(bearer())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private HttpHeaders bearer() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(new AuthUser(ADMIN_ID, true, true)));
    return headers;
  }
}
