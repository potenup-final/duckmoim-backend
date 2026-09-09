package com.duckmoim.admin.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.admin.domain.AuditKind;
import com.duckmoim.admin.domain.AuditLogCursor;
import com.duckmoim.admin.domain.AuditLogListQuery;
import com.duckmoim.admin.domain.AuditTargetType;
import com.duckmoim.admin.service.AuditLogQueryService;
import com.duckmoim.admin.service.AuditLogSlice;
import com.duckmoim.admin.service.AuditLogView;
import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.presentation.ImportSecurity;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 감사 로그 조회의 HTTP 계약 (AD-05).
 *
 * <p>정렬과 커서 경계는 저장소·서비스 통합 테스트가 본다. 여기서는 <b>파라미터가 조회 조건으로 옮겨지는지</b>와 응답 모양만 본다.
 *
 * <p>등급 판정(익명 401 · 일반 계정 403)도 여기 없다 — {@code EndpointGradeTest} 의 권한 표가 {@code GET
 * /api/v1/admin/audit-logs} 행으로 이미 지킨다.
 */
@WebMvcTest(AuditLogController.class)
@ImportSecurity
class AuditLogControllerTest {

  private static final LocalDateTime AT_UTC = LocalDateTime.of(2026, 9, 4, 1, 0);

  private static final long ADMIN_ID = 6L;

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private AuditLogQueryService auditLogQueryService;

  @Captor private ArgumentCaptor<AuditLogListQuery> query;

  @DisplayName("감사 로그를 조회하면 200 과 목록이 돌아온다.")
  @Test
  void getAuditLogs() throws Exception {
    given(auditLogQueryService.findAuditLogs(any())).willReturn(onePage());

    mockMvc
        .perform(get("/api/v1/admin/audit-logs").headers(bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(9))
        .andExpect(jsonPath("$.items[0].actor").value("운영자"))
        .andExpect(jsonPath("$.items[0].kind").value("SECRET_READ"))
        .andExpect(jsonPath("$.items[0].targetType").value("COMMENT"))
        .andExpect(jsonPath("$.items[0].targetId").value(31))
        .andExpect(jsonPath("$.items[0].detail").value("신고 5 처리 중 비밀 댓글 본문 열람"))
        .andExpect(jsonPath("$.hasNext").value(false))
        .andExpect(jsonPath("$.nextCursor").doesNotExist());
  }

  /** 저장은 UTC 이고 응답은 KST 오프셋을 포함한다 (API-컨벤션.md 「필드 표기 규칙」). */
  @DisplayName("행위 시각은 KST 오프셋을 달고 나간다.")
  @Test
  void getAuditLogsInKst() throws Exception {
    given(auditLogQueryService.findAuditLogs(any())).willReturn(onePage());

    mockMvc
        .perform(get("/api/v1/admin/audit-logs").headers(bearer()))
        .andExpect(jsonPath("$.items[0].at").value("2026-09-04T10:00:00+09:00"));
  }

  @DisplayName("커서와 size 가 조회 조건으로 옮겨진다.")
  @Test
  void getAuditLogsWithCursor() throws Exception {
    given(auditLogQueryService.findAuditLogs(any())).willReturn(emptyPage());
    String cursor = new AuditLogCursor(AT_UTC, 9L).encode();

    mockMvc
        .perform(
            get("/api/v1/admin/audit-logs")
                .param("cursor", cursor)
                .param("size", "5")
                .headers(bearer()))
        .andExpect(status().isOk());

    then(auditLogQueryService).should().findAuditLogs(query.capture());
    assertThat(query.getValue().cursor()).isEqualTo(new AuditLogCursor(AT_UTC, 9L));
    assertThat(query.getValue().size()).isEqualTo(5);
  }

  @DisplayName("커서가 없으면 첫 페이지를 읽는다.")
  @Test
  void getAuditLogsWithoutCursor() throws Exception {
    given(auditLogQueryService.findAuditLogs(any())).willReturn(emptyPage());

    mockMvc.perform(get("/api/v1/admin/audit-logs").headers(bearer())).andExpect(status().isOk());

    then(auditLogQueryService).should().findAuditLogs(query.capture());
    assertThat(query.getValue().hasCursor()).isFalse();
    assertThat(query.getValue().size()).isEqualTo(AuditLogListQuery.DEFAULT_SIZE);
  }

  @DisplayName("더 읽을 것이 있으면 nextCursor 가 실린다.")
  @Test
  void getAuditLogsHasNext() throws Exception {
    given(auditLogQueryService.findAuditLogs(any()))
        .willReturn(new AuditLogSlice(List.of(view()), new AuditLogCursor(AT_UTC, 9L), true));

    mockMvc
        .perform(get("/api/v1/admin/audit-logs").headers(bearer()))
        .andExpect(jsonPath("$.hasNext").value(true))
        .andExpect(jsonPath("$.nextCursor").value(new AuditLogCursor(AT_UTC, 9L).encode()));
  }

  @DisplayName("판독할 수 없는 커서는 INVALID_INPUT 400 이다.")
  @Test
  void getAuditLogsWithBrokenCursor() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/audit-logs").param("cursor", "!!broken!!").headers(bearer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  private static AuditLogSlice onePage() {
    return new AuditLogSlice(List.of(view()), null, false);
  }

  private static AuditLogSlice emptyPage() {
    return new AuditLogSlice(List.of(), null, false);
  }

  private static AuditLogView view() {
    return new AuditLogView(
        9L,
        AT_UTC,
        "운영자",
        AuditKind.SECRET_READ,
        AuditTargetType.COMMENT,
        31L,
        "신고 5 처리 중 비밀 댓글 본문 열람");
  }

  private HttpHeaders bearer() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(new AuthUser(ADMIN_ID, true, true)));
    return headers;
  }
}
