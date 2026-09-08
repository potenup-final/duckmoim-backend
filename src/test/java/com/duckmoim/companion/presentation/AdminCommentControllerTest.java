package com.duckmoim.companion.presentation;

import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.presentation.ImportSecurity;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.exception.CommentErrorCode;
import com.duckmoim.companion.service.AdminCommentCommandService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 블라인드의 HTTP 계약 (AD-07).
 *
 * <p>전이와 기록이 실제로 일어나는지는 서비스 통합 테스트가 본다. 여기서는 <b>파라미터가 서비스로 옮겨지는지</b>와 상태 코드만 본다.
 *
 * <p>등급 판정(익명 401 · 일반 계정 403)은 여기 없다 — {@code EndpointGradeTest} 의 권한 표가 {@code POST
 * /api/v1/admin/comments/{commentId}/blind} 행으로 이미 지킨다.
 */
@WebMvcTest(AdminCommentController.class)
@ImportSecurity
class AdminCommentControllerTest {

  private static final long ADMIN_ID = 6L;
  private static final long COMMENT_ID = 31L;

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private AdminCommentCommandService adminCommentCommandService;

  /** 결과 상태가 BLINDED 하나로 정해져 있어 돌려줄 정보가 없다 (API-컨벤션.md 「Status Code 규칙」). */
  @DisplayName("블라인드하면 본문 없이 200 이다.")
  @Test
  void blindComment() throws Exception {
    mockMvc
        .perform(post("/api/v1/admin/comments/{commentId}/blind", COMMENT_ID).headers(bearer()))
        .andExpect(status().isOk());
  }

  /** 인가가 아니라 기록 때문에 받는다. 누가 가렸는지가 감사 로그의 행위자다 (AD-05). */
  @DisplayName("가린 사람의 회원번호가 서비스로 넘어간다.")
  @Test
  void blindCommentCarriesActor() throws Exception {
    mockMvc
        .perform(post("/api/v1/admin/comments/{commentId}/blind", COMMENT_ID).headers(bearer()))
        .andExpect(status().isOk());

    then(adminCommentCommandService).should().blind(COMMENT_ID, ADMIN_ID);
  }

  @DisplayName("ACTIVE 가 아닌 댓글은 COMMENT_NOT_ACTIVE 409 다.")
  @Test
  void blindNotActiveComment() throws Exception {
    willThrow(new BusinessException(CommentErrorCode.COMMENT_NOT_ACTIVE))
        .given(adminCommentCommandService)
        .blind(COMMENT_ID, ADMIN_ID);

    mockMvc
        .perform(post("/api/v1/admin/comments/{commentId}/blind", COMMENT_ID).headers(bearer()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("COMMENT_NOT_ACTIVE"));
  }

  @DisplayName("없는 댓글은 COMMENT_NOT_FOUND 404 다.")
  @Test
  void blindMissingComment() throws Exception {
    willThrow(new BusinessException(CommentErrorCode.COMMENT_NOT_FOUND))
        .given(adminCommentCommandService)
        .blind(COMMENT_ID, ADMIN_ID);

    mockMvc
        .perform(post("/api/v1/admin/comments/{commentId}/blind", COMMENT_ID).headers(bearer()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COMMENT_NOT_FOUND"));
  }

  private HttpHeaders bearer() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(new AuthUser(ADMIN_ID, true, true)));
    return headers;
  }
}
