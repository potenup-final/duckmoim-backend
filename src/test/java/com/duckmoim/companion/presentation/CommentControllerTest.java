package com.duckmoim.companion.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.presentation.ImportSecurity;
import com.duckmoim.companion.domain.CommentStatus;
import com.duckmoim.companion.service.CommentCommandService;
import com.duckmoim.companion.service.CommentEditCommand;
import com.duckmoim.companion.service.WrittenComment;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 수정·삭제의 HTTP 계약 (CM-09 · CM-10).
 *
 * <p>권한 판정과 상태 가드는 도메인 단위 테스트와 서비스 통합 테스트가 본다. 여기서는 <b>요청이 커맨드로 옮겨지는지</b>와 응답 모양만 본다.
 *
 * <p>등급 판정(익명 401 · 가입 미완료 403)도 여기 없다 — {@code EndpointGradeTest} 의 권한 표가 {@code PATCH}·{@code
 * DELETE /api/v1/comments/1} 행으로 이미 지킨다.
 */
@WebMvcTest(CommentController.class)
@ImportSecurity
class CommentControllerTest {

  private static final LocalDateTime WRITTEN_AT_UTC = LocalDateTime.of(2026, 9, 14, 0, 0);

  private static final long COMMENT_ID = 12L;
  private static final long REQUESTER_ID = 7L;

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private CommentCommandService commentCommandService;

  @Captor private ArgumentCaptor<CommentEditCommand> command;

  @DisplayName("댓글을 고치면 200 과 고쳐진 댓글이 돌아온다.")
  @Test
  void editComment() throws Exception {
    given(commentCommandService.edit(any()))
        .willReturn(
            new WrittenComment(
                COMMENT_ID, null, false, CommentStatus.ACTIVE, "저 못 가게 됐어요", WRITTEN_AT_UTC));

    mockMvc
        .perform(editRequest("{\"content\":\"저 못 가게 됐어요\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(12))
        .andExpect(jsonPath("$.content").value("저 못 가게 됐어요"))
        .andExpect(jsonPath("$.createdAt").value("2026-09-14T09:00:00+09:00"));
  }

  /** 요청 본문으로 작성자를 받지 않는 것이 남의 댓글을 고치는 것을 막는 장치다. */
  @DisplayName("토큰의 회원번호가 요청자로 넘어간다.")
  @Test
  void editCommentCarriesRequester() throws Exception {
    given(commentCommandService.edit(any()))
        .willReturn(
            new WrittenComment(
                COMMENT_ID, null, false, CommentStatus.ACTIVE, "고친다", WRITTEN_AT_UTC));

    mockMvc
        .perform(editRequest("{\"content\":\"고친다\",\"secret\":false}"))
        .andExpect(status().isOk());

    then(commentCommandService).should().edit(command.capture());
    assertThat(command.getValue().requesterId()).isEqualTo(REQUESTER_ID);
    assertThat(command.getValue().commentId()).isEqualTo(COMMENT_ID);
    assertThat(command.getValue().secret()).isFalse();
  }

  /** 안 보내면 판정하지 않는다 — 프론트가 본문만 보내는 경우다. */
  @DisplayName("비밀 여부를 안 보내면 커맨드에 null 로 간다.")
  @Test
  void editCommentWithoutSecret() throws Exception {
    given(commentCommandService.edit(any()))
        .willReturn(
            new WrittenComment(
                COMMENT_ID, null, false, CommentStatus.ACTIVE, "고친다", WRITTEN_AT_UTC));

    mockMvc.perform(editRequest("{\"content\":\"고친다\"}")).andExpect(status().isOk());

    then(commentCommandService).should().edit(command.capture());
    assertThat(command.getValue().secret()).isNull();
  }

  @DisplayName("본문이 500자를 넘으면 고칠 수 없다.")
  @Test
  void editComment_contentIsTooLong() throws Exception {
    mockMvc
        .perform(editRequest("{\"content\":\"" + "가".repeat(501) + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        .andExpect(jsonPath("$.message").value("본문은 500자 이하여야 합니다."));
  }

  @DisplayName("본문이 비어 있으면 고칠 수 없다.")
  @Test
  void editComment_contentIsBlank() throws Exception {
    mockMvc
        .perform(editRequest("{\"content\":\"   \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  /** 지운 댓글의 무엇을 돌려줄 이유가 없다. 자리표시자로 남을지는 조회가 판정한다 (CM-11). */
  @DisplayName("댓글을 지우면 본문 없이 200 이다.")
  @Test
  void deleteComment() throws Exception {
    mockMvc
        .perform(delete("/api/v1/comments/{commentId}", COMMENT_ID).headers(bearer()))
        .andExpect(status().isOk())
        .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());

    then(commentCommandService).should().delete(eq(COMMENT_ID), eq(REQUESTER_ID));
  }

  private MockHttpServletRequestBuilder editRequest(String body) {
    return patch("/api/v1/comments/{commentId}", COMMENT_ID)
        .headers(bearer())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private HttpHeaders bearer() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(new AuthUser(REQUESTER_ID, true, false)));
    return headers;
  }
}
