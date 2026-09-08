package com.duckmoim.companion.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.presentation.ImportSecurity;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.domain.Comment;
import com.duckmoim.companion.domain.CommentStatus;
import com.duckmoim.companion.exception.CommentErrorCode;
import com.duckmoim.companion.service.AdminCommentReadService;
import com.duckmoim.companion.service.AdminCommentView;
import com.duckmoim.identity.domain.LastSeen;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 관리자 댓글 열람의 HTTP 계약 (CM-17).
 *
 * <p>기록이 실제로 남는지는 서비스 통합 테스트가 본다. 여기서는 <b>파라미터가 서비스로 옮겨지는지</b>와 응답 모양만 본다.
 *
 * <p>등급 판정(익명 401 · 일반 계정 403)도 여기 없다 — {@code EndpointGradeTest} 의 권한 표가 {@code GET
 * /api/v1/admin/comments/{commentId}} 행으로 이미 지킨다. 그 표는 위키의 권한 표를 옮긴 것이고, 이 컨트롤러가 생기면서 진짜 요청을 실행하게
 * 된다.
 */
@WebMvcTest(AdminCommentController.class)
@ImportSecurity
class AdminCommentControllerTest {

  private static final LocalDateTime WRITTEN_AT_UTC = LocalDateTime.of(2026, 9, 4, 1, 0);

  private static final long ADMIN_ID = 6L;
  private static final long COMMENT_ID = 31L;
  private static final long POST_ID = 12L;
  private static final long AUTHOR_ID = 2L;

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private AdminCommentReadService adminCommentReadService;

  @Captor private ArgumentCaptor<Long> reportId;

  /** 이 엔드포인트가 있는 이유다. 비밀 댓글인데 본문이 그대로 실린다. */
  @DisplayName("비밀 댓글을 열면 200 과 본문이 돌아온다.")
  @Test
  void getComment() throws Exception {
    given(adminCommentReadService.read(eq(COMMENT_ID), eq(ADMIN_ID), any()))
        .willReturn(view(true, CommentStatus.ACTIVE));

    mockMvc
        .perform(get("/api/v1/admin/comments/{commentId}", COMMENT_ID).headers(bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(COMMENT_ID))
        .andExpect(jsonPath("$.postId").value(POST_ID))
        .andExpect(jsonPath("$.secret").value(true))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.content").value("연락처는 010-0000-0000 입니다"))
        .andExpect(jsonPath("$.author.id").value(AUTHOR_ID))
        .andExpect(jsonPath("$.author.nickname").value("댓글덕후"))
        .andExpect(jsonPath("$.author.lastSeen").value("TODAY"));
  }

  /** 조회 경로의 content 는 권한이 없으면 키째 사라진다 (CM-05). 이 경로에는 그 분기가 없다. */
  @DisplayName("본문 키가 사라지는 분기가 없다.")
  @Test
  void getCommentAlwaysCarriesContent() throws Exception {
    given(adminCommentReadService.read(any(), any(), any()))
        .willReturn(view(true, CommentStatus.ACTIVE));

    mockMvc
        .perform(get("/api/v1/admin/comments/{commentId}", COMMENT_ID).headers(bearer()))
        .andExpect(jsonPath("$.content").exists());
  }

  @DisplayName("소프트 삭제·블라인드된 댓글도 본문과 상태가 실린다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = CommentStatus.class,
      names = {"DELETED", "BLINDED"})
  void getInactiveComment(CommentStatus status) throws Exception {
    given(adminCommentReadService.read(any(), any(), any())).willReturn(view(true, status));

    mockMvc
        .perform(get("/api/v1/admin/comments/{commentId}", COMMENT_ID).headers(bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(status.name()))
        .andExpect(jsonPath("$.content").exists());
  }

  /** 저장은 UTC 이고 응답은 KST 오프셋을 포함한다 (API-컨벤션.md 「필드 표기 규칙」). */
  @DisplayName("작성 시각은 KST 오프셋을 달고 나간다.")
  @Test
  void getCommentInKst() throws Exception {
    given(adminCommentReadService.read(any(), any(), any()))
        .willReturn(view(true, CommentStatus.ACTIVE));

    mockMvc
        .perform(get("/api/v1/admin/comments/{commentId}", COMMENT_ID).headers(bearer()))
        .andExpect(jsonPath("$.createdAt").value("2026-09-04T10:00:00+09:00"));
  }

  @DisplayName("신고 번호를 주면 그대로 서비스에 넘어간다.")
  @Test
  void getCommentWithReportId() throws Exception {
    given(adminCommentReadService.read(any(), any(), any()))
        .willReturn(view(true, CommentStatus.ACTIVE));

    mockMvc
        .perform(
            get("/api/v1/admin/comments/{commentId}", COMMENT_ID)
                .param("reportId", "5")
                .headers(bearer()))
        .andExpect(status().isOk());

    then(adminCommentReadService).should().read(eq(COMMENT_ID), eq(ADMIN_ID), reportId.capture());
    assertThat(reportId.getValue()).isEqualTo(5L);
  }

  @DisplayName("신고 번호는 없어도 된다.")
  @Test
  void getCommentWithoutReportId() throws Exception {
    given(adminCommentReadService.read(any(), any(), any()))
        .willReturn(view(true, CommentStatus.ACTIVE));

    mockMvc
        .perform(get("/api/v1/admin/comments/{commentId}", COMMENT_ID).headers(bearer()))
        .andExpect(status().isOk());

    then(adminCommentReadService).should().read(eq(COMMENT_ID), eq(ADMIN_ID), reportId.capture());
    assertThat(reportId.getValue()).isNull();
  }

  /** 인가가 아니라 기록 때문에 받는다. 누가 열었는지가 감사 로그의 행위자다 (AD-05). */
  @DisplayName("연 사람의 회원번호가 서비스로 넘어간다.")
  @Test
  void getCommentCarriesActor() throws Exception {
    given(adminCommentReadService.read(any(), any(), any()))
        .willReturn(view(true, CommentStatus.ACTIVE));

    mockMvc
        .perform(get("/api/v1/admin/comments/{commentId}", COMMENT_ID).headers(bearer()))
        .andExpect(status().isOk());

    then(adminCommentReadService).should().read(COMMENT_ID, ADMIN_ID, null);
  }

  @DisplayName("없는 댓글은 COMMENT_NOT_FOUND 404 다.")
  @Test
  void getMissingComment() throws Exception {
    given(adminCommentReadService.read(any(), any(), any()))
        .willThrow(new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND));

    mockMvc
        .perform(get("/api/v1/admin/comments/{commentId}", COMMENT_ID).headers(bearer()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COMMENT_NOT_FOUND"));
  }

  private static AdminCommentView view(boolean secret, CommentStatus status) {
    Comment comment = Comment.root(POST_ID, AUTHOR_ID, "연락처는 010-0000-0000 입니다", secret);

    ReflectionTestUtils.setField(comment, "id", COMMENT_ID);
    ReflectionTestUtils.setField(comment, "createdAt", WRITTEN_AT_UTC);
    ReflectionTestUtils.setField(comment, "status", status);

    return new AdminCommentView(comment, "댓글덕후", null, LastSeen.TODAY);
  }

  private HttpHeaders bearer() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(new AuthUser(ADMIN_ID, true, true)));
    return headers;
  }
}
