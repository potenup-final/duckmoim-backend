package com.duckmoim.safety.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.presentation.ImportSecurity;
import com.duckmoim.safety.domain.SanctionCursor;
import com.duckmoim.safety.domain.SanctionKind;
import com.duckmoim.safety.domain.SanctionListQuery;
import com.duckmoim.safety.service.SanctionListService;
import com.duckmoim.safety.service.SanctionListView;
import com.duckmoim.safety.service.SanctionSlice;
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
 * 제재 중인 회원 목록의 HTTP 계약 (AD-10).
 *
 * <p>정렬 · 활성 판정 · 커서 경계는 저장소 · 서비스 통합 테스트가 본다. 여기서는 <b>파라미터가 조회 조건으로 옮겨지는지</b>와 응답 모양만 본다.
 *
 * <p>등급 판정(익명 401 · 일반 계정 403)도 여기 없다 — {@code EndpointGradeTest} 의 권한 표가 이 경로 행으로 이미 지킨다.
 */
@WebMvcTest(AdminSanctionListController.class)
@ImportSecurity
class AdminSanctionListControllerTest {

  private static final LocalDateTime ISSUED_AT_UTC = LocalDateTime.of(2026, 9, 4, 1, 0);
  private static final LocalDateTime EXPIRES_AT_UTC = LocalDateTime.of(2026, 9, 11, 0, 0);

  private static final long ADMIN_ID = 6L;
  private static final long SANCTION_ID = 12L;
  private static final long USER_ID = 41L;

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private SanctionListService sanctionListService;

  @Captor private ArgumentCaptor<SanctionKind> kind;
  @Captor private ArgumentCaptor<SanctionCursor> cursor;
  @Captor private ArgumentCaptor<Integer> size;

  @DisplayName("제재 목록을 조회하면 200 과 목록이 돌아온다.")
  @Test
  void getSanctions() throws Exception {
    given(sanctionListService.findSanctions(any(), any(), anyInt())).willReturn(onePage());

    mockMvc
        .perform(get("/api/v1/admin/sanctions").headers(bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].sanctionId").value(SANCTION_ID))
        .andExpect(jsonPath("$.items[0].userId").value(USER_ID))
        .andExpect(jsonPath("$.items[0].nickname").value("덕후01"))
        .andExpect(jsonPath("$.items[0].kind").value("SUSPENDED"))
        .andExpect(jsonPath("$.items[0].reason").value("약속 불이행 신고가 세 건 접수되었습니다"))
        .andExpect(jsonPath("$.hasNext").value(false))
        .andExpect(jsonPath("$.nextCursor").doesNotExist());
  }

  /** 저장은 UTC 이고 응답은 KST 오프셋을 포함한다 (API-컨벤션.md 「필드 표기 규칙」). */
  @DisplayName("제재 시각은 KST 오프셋을 달고 나간다.")
  @Test
  void getSanctionsInKst() throws Exception {
    given(sanctionListService.findSanctions(any(), any(), anyInt())).willReturn(onePage());

    mockMvc
        .perform(get("/api/v1/admin/sanctions").headers(bearer()))
        .andExpect(jsonPath("$.items[0].issuedAt").value("2026-09-04T10:00:00+09:00"))
        .andExpect(jsonPath("$.items[0].until").value("2026-09-11T09:00:00+09:00"))
        .andExpect(jsonPath("$.items[0].expiresAt").value("2026-09-11T09:00:00+09:00"));
  }

  /** 화면이 「기한 없음」을 그리는 자리다. 만료를 지어내 내리지 않는다. */
  @DisplayName("스스로 풀리지 않는 제재는 만료가 비어서 나간다.")
  @Test
  void getSanctionsWithoutExpiry() throws Exception {
    given(sanctionListService.findSanctions(any(), any(), anyInt()))
        .willReturn(new SanctionSlice(List.of(banned()), null, false));

    mockMvc
        .perform(get("/api/v1/admin/sanctions").headers(bearer()))
        .andExpect(jsonPath("$.items[0].kind").value("BANNED"))
        .andExpect(jsonPath("$.items[0].until").doesNotExist())
        .andExpect(jsonPath("$.items[0].expiresAt").doesNotExist());
  }

  @DisplayName("종류 · 커서 · size 가 조회 조건으로 옮겨진다.")
  @Test
  void getSanctionsWithFilters() throws Exception {
    given(sanctionListService.findSanctions(any(), any(), anyInt())).willReturn(emptyPage());
    String encoded = new SanctionCursor(EXPIRES_AT_UTC, SANCTION_ID).encode();

    mockMvc
        .perform(
            get("/api/v1/admin/sanctions")
                .param("kind", "SUSPENDED")
                .param("cursor", encoded)
                .param("size", "5")
                .headers(bearer()))
        .andExpect(status().isOk());

    then(sanctionListService)
        .should()
        .findSanctions(kind.capture(), cursor.capture(), size.capture());
    assertThat(kind.getValue()).isEqualTo(SanctionKind.SUSPENDED);
    assertThat(cursor.getValue()).isEqualTo(new SanctionCursor(EXPIRES_AT_UTC, SANCTION_ID));
    assertThat(size.getValue()).isEqualTo(5);
  }

  /** 「활성 제재를 훑는다」가 AD-10 의 기본이라 필터 없는 쪽이 기본이다. */
  @DisplayName("종류를 주지 않으면 거르지 않는다.")
  @Test
  void getSanctionsWithoutKind() throws Exception {
    given(sanctionListService.findSanctions(any(), any(), anyInt())).willReturn(emptyPage());

    mockMvc.perform(get("/api/v1/admin/sanctions").headers(bearer())).andExpect(status().isOk());

    then(sanctionListService)
        .should()
        .findSanctions(kind.capture(), cursor.capture(), size.capture());
    assertThat(kind.getValue()).isNull();
    assertThat(cursor.getValue()).isNull();
    // 안 준 size 는 0 으로 넘어가고 조회 조건이 기본값으로 자른다 — 기본값을 presentation 이 정하지 않는다.
    assertThat(size.getValue()).isZero();
    assertThat(new SanctionListQuery(null, ISSUED_AT_UTC, null, 0).size())
        .isEqualTo(SanctionListQuery.DEFAULT_SIZE);
  }

  @DisplayName("더 읽을 것이 있으면 nextCursor 가 실린다.")
  @Test
  void getSanctionsHasNext() throws Exception {
    SanctionCursor next = new SanctionCursor(EXPIRES_AT_UTC, SANCTION_ID);
    given(sanctionListService.findSanctions(any(), any(), anyInt()))
        .willReturn(new SanctionSlice(List.of(view()), next, true));

    mockMvc
        .perform(get("/api/v1/admin/sanctions").headers(bearer()))
        .andExpect(jsonPath("$.hasNext").value(true))
        .andExpect(jsonPath("$.nextCursor").value(next.encode()));
  }

  @DisplayName("판독할 수 없는 커서는 INVALID_INPUT 400 이다.")
  @Test
  void getSanctionsWithBrokenCursor() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/sanctions").param("cursor", "!!broken!!").headers(bearer()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  private static SanctionSlice onePage() {
    return new SanctionSlice(List.of(view()), null, false);
  }

  private static SanctionSlice emptyPage() {
    return new SanctionSlice(List.of(), null, false);
  }

  private static SanctionListView view() {
    return new SanctionListView(
        SANCTION_ID,
        USER_ID,
        "덕후01",
        SanctionKind.SUSPENDED,
        "약속 불이행 신고가 세 건 접수되었습니다",
        ISSUED_AT_UTC,
        EXPIRES_AT_UTC,
        EXPIRES_AT_UTC);
  }

  private static SanctionListView banned() {
    return new SanctionListView(
        SANCTION_ID, USER_ID, "덕후01", SanctionKind.BANNED, "반복 신고", ISSUED_AT_UTC, null, null);
  }

  private HttpHeaders bearer() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(new AuthUser(ADMIN_ID, true, true)));
    return headers;
  }
}
