package com.duckmoim.safety.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.presentation.ImportSecurity;
import com.duckmoim.safety.domain.ReportReason;
import com.duckmoim.safety.domain.ReportTargetType;
import com.duckmoim.safety.service.ReportCommand;
import com.duckmoim.safety.service.ReportCommandService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 접수의 HTTP 계약 (SF-01 · SF-02 · SF-07).
 *
 * <p>대상×사유 조합과 중복 차단은 도메인 단위 테스트와 서비스 통합 테스트가 본다. 여기서는 <b>요청이 커맨드로 옮겨지는지</b>와, 값 자체가 enum 에 없을 때의
 * 실패가 조합 실패와 <b>다른 코드</b>로 나가는지를 본다.
 *
 * <p>등급 판정은 없다 — {@code EndpointGradeTest} 의 권한 표가 {@code POST /api/v1/reports} 행으로 이미 지킨다.
 */
@WebMvcTest(ReportController.class)
@ImportSecurity
class ReportControllerTest {

  private static final long REPORTER_ID = 7L;

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private ReportCommandService reportCommandService;

  @Captor private ArgumentCaptor<ReportCommand> command;

  @DisplayName("신고가 접수되면 200 과 접수 id 가 돌아온다.")
  @Test
  void report() throws Exception {
    given(reportCommandService.report(any())).willReturn(31L);

    mockMvc
        .perform(
            reportRequest(
                "{\"targetType\":\"COMMENT\",\"targetId\":12,\"reason\":\"INAPPROPRIATE\","
                    + "\"detail\":\"연락처를 요구합니다\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(31));
  }

  /** 신고자를 요청 본문으로 받지 않는 것이 남의 이름으로 신고하는 것을 막는 장치다. */
  @DisplayName("토큰의 회원번호가 신고자로 넘어간다.")
  @Test
  void reportCarriesReporter() throws Exception {
    given(reportCommandService.report(any())).willReturn(31L);

    mockMvc
        .perform(reportRequest("{\"targetType\":\"USER\",\"targetId\":42,\"reason\":\"NO_SHOW\"}"))
        .andExpect(status().isOk());

    then(reportCommandService).should().report(command.capture());
    assertThat(command.getValue().reporterId()).isEqualTo(REPORTER_ID);
    assertThat(command.getValue().targetType()).isEqualTo(ReportTargetType.USER);
    assertThat(command.getValue().reason()).isEqualTo(ReportReason.NO_SHOW);
    assertThat(command.getValue().detail()).isNull();
  }

  /**
   * <b>조합 실패와 다른 실패다.</b> 값 자체가 enum 에 없으면 역직렬화가 먼저 걸러 {@code INVALID_INPUT} 이고, 대상과의 조합이 틀리면
   * 애그리게이트가 {@code REPORT_REASON_INVALID} 를 던진다.
   */
  @DisplayName("사유 값이 enum 에 없으면 접수할 수 없다.")
  @Test
  void report_reasonIsUnknown() throws Exception {
    mockMvc
        .perform(
            reportRequest("{\"targetType\":\"USER\",\"targetId\":42,\"reason\":\"IMPERSONATION\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  @DisplayName("대상 종류가 enum 에 없으면 접수할 수 없다.")
  @Test
  void report_targetTypeIsUnknown() throws Exception {
    mockMvc
        .perform(reportRequest("{\"targetType\":\"EVENT\",\"targetId\":42,\"reason\":\"ABUSE\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  @DisplayName("대상이 없으면 접수할 수 없다.")
  @Test
  void report_targetIdIsMissing() throws Exception {
    mockMvc
        .perform(reportRequest("{\"targetType\":\"USER\",\"reason\":\"ABUSE\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  @DisplayName("상세가 500자를 넘으면 접수할 수 없다.")
  @Test
  void report_detailIsTooLong() throws Exception {
    mockMvc
        .perform(
            reportRequest(
                "{\"targetType\":\"USER\",\"targetId\":42,\"reason\":\"ABUSE\",\"detail\":\""
                    + "가".repeat(501)
                    + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        .andExpect(jsonPath("$.message").value("상세는 500자 이하여야 합니다."));
  }

  private MockHttpServletRequestBuilder reportRequest(String body) {
    return post("/api/v1/reports")
        .headers(bearer())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private HttpHeaders bearer() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.issueAccessToken(new AuthUser(REPORTER_ID, true, false)));
    return headers;
  }
}
