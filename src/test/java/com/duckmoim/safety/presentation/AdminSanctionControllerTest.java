package com.duckmoim.safety.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.presentation.ImportSecurity;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.safety.domain.SanctionKind;
import com.duckmoim.safety.exception.SanctionErrorCode;
import com.duckmoim.safety.service.SanctionCommand;
import com.duckmoim.safety.service.SanctionCommandService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * 제재 API 의 HTTP 계약 (AD-04).
 *
 * <p>전이와 감사 로그는 서비스 통합 테스트가 본다. 여기서는 <b>요청이 명령으로 옮겨지는지</b>와 상태 코드만 본다.
 *
 * <p>등급 판정(익명 401 · 일반 계정 403)은 여기 없다 — {@code EndpointGradeTest} 의 권한 표가 두 경로 행으로 이미 지킨다.
 */
@WebMvcTest(AdminSanctionController.class)
@ImportSecurity
class AdminSanctionControllerTest {

  private static final long ADMIN_ID = 6L;
  private static final long USER_ID = 42L;
  private static final long SANCTION_ID = 7L;

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private SanctionCommandService sanctionCommandService;

  @Captor private ArgumentCaptor<SanctionCommand> command;

  @DisplayName("제재하면 제재 번호가 돌아온다.")
  @Test
  void sanction() throws Exception {
    given(sanctionCommandService.sanction(any())).willReturn(SANCTION_ID);

    mockMvc
        .perform(sanctionWith("{\"kind\":\"WARNED\",\"reason\":\"약속 불이행\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sanctionId").value(SANCTION_ID));
  }

  @DisplayName("종류 · 사유 · 관리자가 명령으로 옮겨진다.")
  @Test
  void sanctionCarriesFields() throws Exception {
    given(sanctionCommandService.sanction(any())).willReturn(SANCTION_ID);

    mockMvc
        .perform(sanctionWith("{\"kind\":\"BANNED\",\"reason\":\"약속 불이행\"}"))
        .andExpect(status().isOk());

    then(sanctionCommandService).should().sanction(command.capture());
    assertThat(command.getValue().userId()).isEqualTo(USER_ID);
    assertThat(command.getValue().kind()).isEqualTo(SanctionKind.BANNED);
    assertThat(command.getValue().reason()).isEqualTo("약속 불이행");
    assertThat(command.getValue().adminUserId()).isEqualTo(ADMIN_ID);
    assertThat(command.getValue().until()).isNull();
  }

  /** 저장은 UTC 다. 오프셋을 달고 들어온 값이 그 시점의 UTC 로 옮겨져야 한다 (도메인 4장). */
  @DisplayName("해제 시각은 UTC 로 옮겨져 넘어간다.")
  @Test
  void sanctionConvertsUntilToUtc() throws Exception {
    given(sanctionCommandService.sanction(any())).willReturn(SANCTION_ID);

    mockMvc
        .perform(
            sanctionWith(
                "{\"kind\":\"SUSPENDED\",\"reason\":\"약속 불이행\","
                    + "\"until\":\"2026-09-11T00:00:00+09:00\"}"))
        .andExpect(status().isOk());

    then(sanctionCommandService).should().sanction(command.capture());
    assertThat(command.getValue().until()).isEqualTo(LocalDateTime.of(2026, 9, 10, 15, 0));
  }

  /** 「reason 은 본인에게 그대로 보이므로 필수다」 (화면 계약). */
  @DisplayName("사유가 없으면 400 이다.")
  @Test
  void sanctionWithoutReason() throws Exception {
    mockMvc.perform(sanctionWith("{\"kind\":\"WARNED\"}")).andExpect(status().isBadRequest());
  }

  @DisplayName("종류가 없으면 400 이다.")
  @Test
  void sanctionWithoutKind() throws Exception {
    mockMvc.perform(sanctionWith("{\"reason\":\"약속 불이행\"}")).andExpect(status().isBadRequest());
  }

  /** NONE 은 SanctionKind 에 아예 없어 역직렬화가 먼저 거른다. */
  @DisplayName("NONE 은 걸 수 없다.")
  @Test
  void sanctionWithNone() throws Exception {
    mockMvc
        .perform(sanctionWith("{\"kind\":\"NONE\",\"reason\":\"약속 불이행\"}"))
        .andExpect(status().isBadRequest());
  }

  @DisplayName("이미 제재 중이면 409 다.")
  @Test
  void sanctionAlreadyActive() throws Exception {
    willThrow(new BusinessException(SanctionErrorCode.SANCTION_ALREADY_ACTIVE))
        .given(sanctionCommandService)
        .sanction(any());

    mockMvc
        .perform(sanctionWith("{\"kind\":\"WARNED\",\"reason\":\"약속 불이행\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SANCTION_ALREADY_ACTIVE"));
  }

  @DisplayName("제재를 풀면 본문 없이 200 이다.")
  @Test
  void release() throws Exception {
    mockMvc
        .perform(
            delete("/api/v1/admin/users/{userId}/sanctions/{sanctionId}", USER_ID, SANCTION_ID)
                .headers(bearer()))
        .andExpect(status().isOk());

    then(sanctionCommandService).should().release(USER_ID, SANCTION_ID, ADMIN_ID);
  }

  @DisplayName("없는 제재를 풀면 404 다.")
  @Test
  void releaseMissing() throws Exception {
    willThrow(new BusinessException(SanctionErrorCode.SANCTION_NOT_FOUND))
        .given(sanctionCommandService)
        .release(USER_ID, SANCTION_ID, ADMIN_ID);

    mockMvc
        .perform(
            delete("/api/v1/admin/users/{userId}/sanctions/{sanctionId}", USER_ID, SANCTION_ID)
                .headers(bearer()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SANCTION_NOT_FOUND"));
  }

  private RequestBuilder sanctionWith(String body) {
    return post("/api/v1/admin/users/{userId}/sanctions", USER_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .headers(bearer());
  }

  private HttpHeaders bearer() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(new AuthUser(ADMIN_ID, true, true)));
    return headers;
  }
}
