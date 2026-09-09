package com.duckmoim.safety.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.presentation.ImportSecurity;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.safety.domain.ReportCursor;
import com.duckmoim.safety.domain.ReportListQuery;
import com.duckmoim.safety.domain.ReportReason;
import com.duckmoim.safety.domain.ReportResult;
import com.duckmoim.safety.domain.ReportStatus;
import com.duckmoim.safety.domain.ReportTargetType;
import com.duckmoim.safety.exception.ReportErrorCode;
import com.duckmoim.safety.service.ReportHandleCommand;
import com.duckmoim.safety.service.ReportHandleService;
import com.duckmoim.safety.service.ReportQueryService;
import com.duckmoim.safety.service.ReportSlice;
import com.duckmoim.safety.service.ReportView;
import java.time.LocalDateTime;
import java.util.List;
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

/**
 * 백오피스 신고 큐의 HTTP 계약 (AD-02 · AD-03).
 *
 * <p>정렬 · 커서 경계 · 전이 규칙은 저장소 · 서비스 통합 테스트가 본다. 여기서는 <b>파라미터가 조회 조건과 명령으로 옮겨지는지</b>와 응답 모양만 본다.
 *
 * <p>등급 판정(익명 401 · 일반 계정 403)도 여기 없다 — {@code EndpointGradeTest} 의 권한 표가 두 경로 행으로 이미 지킨다.
 */
@WebMvcTest(AdminReportController.class)
@ImportSecurity
class AdminReportControllerTest {

  private static final LocalDateTime CREATED_AT_UTC = LocalDateTime.of(2026, 8, 31, 0, 12);

  private static final long ADMIN_ID = 6L;
  private static final long REPORT_ID = 5L;

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private ReportQueryService reportQueryService;
  @MockitoBean private ReportHandleService reportHandleService;

  @Captor private ArgumentCaptor<ReportListQuery> query;
  @Captor private ArgumentCaptor<ReportHandleCommand> command;

  @DisplayName("신고 목록을 조회하면 200 과 목록이 돌아온다.")
  @Test
  void getReports() throws Exception {
    given(reportQueryService.findReports(any())).willReturn(onePage());

    mockMvc
        .perform(get("/api/v1/admin/reports").headers(bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(REPORT_ID))
        .andExpect(jsonPath("$.items[0].targetType").value("USER"))
        .andExpect(jsonPath("$.items[0].subject").value("조용한덕후"))
        .andExpect(jsonPath("$.items[0].reporter").value("밤샘예매"))
        .andExpect(jsonPath("$.items[0].status").value("PENDING"))
        .andExpect(jsonPath("$.items[0].secret").value(false))
        .andExpect(jsonPath("$.hasNext").value(false))
        .andExpect(jsonPath("$.nextCursor").doesNotExist());
  }

  /** 저장은 UTC 이고 응답은 KST 오프셋을 포함한다 (API-컨벤션.md 「필드 표기 규칙」). */
  @DisplayName("접수 시각은 KST 오프셋을 달고 나간다.")
  @Test
  void getReportsInKst() throws Exception {
    given(reportQueryService.findReports(any())).willReturn(onePage());

    mockMvc
        .perform(get("/api/v1/admin/reports").headers(bearer()))
        .andExpect(jsonPath("$.items[0].createdAt").value("2026-08-31T09:12:00+09:00"));
  }

  @DisplayName("상태 · 커서 · size 가 조회 조건으로 옮겨진다.")
  @Test
  void getReportsWithFilters() throws Exception {
    given(reportQueryService.findReports(any())).willReturn(emptyPage());
    String cursor = new ReportCursor(CREATED_AT_UTC, REPORT_ID).encode();

    mockMvc
        .perform(
            get("/api/v1/admin/reports")
                .param("status", "PROCESSING")
                .param("cursor", cursor)
                .param("size", "5")
                .headers(bearer()))
        .andExpect(status().isOk());

    then(reportQueryService).should().findReports(query.capture());
    assertThat(query.getValue().status()).isEqualTo(ReportStatus.PROCESSING);
    assertThat(query.getValue().cursor()).isEqualTo(new ReportCursor(CREATED_AT_UTC, REPORT_ID));
    assertThat(query.getValue().size()).isEqualTo(5);
  }

  /** 「접수 건 전량 조회」가 AD-02 의 검증 기준이라 필터 없는 쪽이 기본이다. */
  @DisplayName("상태를 주지 않으면 거르지 않는다.")
  @Test
  void getReportsWithoutStatus() throws Exception {
    given(reportQueryService.findReports(any())).willReturn(emptyPage());

    mockMvc.perform(get("/api/v1/admin/reports").headers(bearer())).andExpect(status().isOk());

    then(reportQueryService).should().findReports(query.capture());
    assertThat(query.getValue().hasStatus()).isFalse();
    assertThat(query.getValue().size()).isEqualTo(ReportListQuery.DEFAULT_SIZE);
  }

  @DisplayName("더 읽을 것이 있으면 nextCursor 가 실린다.")
  @Test
  void getReportsHasNext() throws Exception {
    given(reportQueryService.findReports(any()))
        .willReturn(new ReportSlice(List.of(view()), new ReportCursor(CREATED_AT_UTC, 5L), true));

    mockMvc
        .perform(get("/api/v1/admin/reports").headers(bearer()))
        .andExpect(jsonPath("$.hasNext").value(true))
        .andExpect(jsonPath("$.nextCursor").value(new ReportCursor(CREATED_AT_UTC, 5L).encode()));
  }

  @DisplayName("판독할 수 없는 커서는 INVALID_INPUT 400 이다.")
  @Test
  void getReportsWithBrokenCursor() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/reports").param("cursor", "!!broken!!").headers(bearer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  @DisplayName("신고를 처리하면 본문 없이 200 이다.")
  @Test
  void handleReport() throws Exception {
    mockMvc.perform(patchWith("{\"status\":\"PROCESSING\"}")).andExpect(status().isOk());

    then(reportHandleService).should().handle(command.capture());
    assertThat(command.getValue().reportId()).isEqualTo(REPORT_ID);
    assertThat(command.getValue().status()).isEqualTo(ReportStatus.PROCESSING);
    assertThat(command.getValue().adminUserId()).isEqualTo(ADMIN_ID);
  }

  @DisplayName("종결 사유와 메모가 명령으로 옮겨진다.")
  @Test
  void handleReportWithResult() throws Exception {
    mockMvc
        .perform(
            patchWith("{\"status\":\"RESOLVED\",\"result\":\"NO_ACTION\",\"memo\":\"사유 확인 안 됨\"}"))
        .andExpect(status().isOk());

    then(reportHandleService).should().handle(command.capture());
    assertThat(command.getValue().result()).isEqualTo(ReportResult.NO_ACTION);
    assertThat(command.getValue().memo()).isEqualTo("사유 확인 안 됨");
  }

  @DisplayName("상태를 주지 않으면 400 이다.")
  @Test
  void handleReportWithoutStatus() throws Exception {
    mockMvc.perform(patchWith("{\"result\":\"NO_ACTION\"}")).andExpect(status().isBadRequest());
  }

  /** AD-03 의 검증 기준이 걸린 자리다 — 「RESOLVED 건 재처리 시 409」. */
  @DisplayName("이미 종결된 신고는 REPORT_ALREADY_HANDLED 409 다.")
  @Test
  void handleAlreadyResolvedReport() throws Exception {
    willThrow(new BusinessException(ReportErrorCode.REPORT_ALREADY_HANDLED))
        .given(reportHandleService)
        .handle(any());

    mockMvc
        .perform(patchWith("{\"status\":\"RESOLVED\",\"result\":\"NO_ACTION\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REPORT_ALREADY_HANDLED"));
  }

  @DisplayName("없는 신고는 REPORT_NOT_FOUND 404 다.")
  @Test
  void handleMissingReport() throws Exception {
    willThrow(new BusinessException(ReportErrorCode.REPORT_NOT_FOUND))
        .given(reportHandleService)
        .handle(any());

    mockMvc
        .perform(patchWith("{\"status\":\"PROCESSING\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
  }

  private org.springframework.test.web.servlet.RequestBuilder patchWith(String body) {
    return patch("/api/v1/admin/reports/{reportId}", REPORT_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .headers(bearer());
  }

  private static ReportSlice onePage() {
    return new ReportSlice(List.of(view()), null, false);
  }

  private static ReportSlice emptyPage() {
    return new ReportSlice(List.of(), null, false);
  }

  private static ReportView view() {
    return new ReportView(
        REPORT_ID,
        ReportTargetType.USER,
        42L,
        "조용한덕후",
        ReportReason.NO_SHOW,
        "만나기로 한 날 연락이 끊겼습니다.",
        "밤샘예매",
        CREATED_AT_UTC,
        ReportStatus.PENDING,
        null,
        null,
        false);
  }

  private HttpHeaders bearer() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(new AuthUser(ADMIN_ID, true, true)));
    return headers;
  }
}
