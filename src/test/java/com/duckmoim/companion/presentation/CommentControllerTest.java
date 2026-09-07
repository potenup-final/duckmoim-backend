package com.duckmoim.companion.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.companion.domain.CommentStatus;
import com.duckmoim.companion.service.CommentCommandService;
import com.duckmoim.companion.service.WrittenComment;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 계약만 본다 (테스트 컨벤션 · 테스트 계층).
 *
 * <p>열린 글 판정 · 깊이 · 부모 확인은 서비스 통합 테스트가 본다. 여기서 다시 검증하면 같은 규칙을 두 곳에서 관리하게 된다.
 *
 * <p>요청자 헤더 검증이 여기 있는 이유 — 그 헤더는 인증이 붙기 전의 임시 통로이고, 임시인 것도 계약이라 없을 때 무엇이 나가는지가 정해져 있어야 한다.
 */
@WebMvcTest(CommentController.class)
class CommentControllerTest {

  /** 저장은 UTC 다. 응답에서 KST 오프셋이 붙어 09:00 으로 나가야 한다. */
  private static final LocalDateTime WRITTEN_AT_UTC = LocalDateTime.of(2026, 9, 14, 0, 0);

  @Autowired private MockMvc mockMvc;

  @MockitoBean private CommentCommandService commentCommandService;

  @DisplayName("댓글을 작성하면 200 과 작성된 댓글이 돌아온다.")
  @Test
  void writeComment() throws Exception {
    given(commentCommandService.write(any()))
        .willReturn(
            new WrittenComment(12L, null, true, CommentStatus.ACTIVE, "연락처 남길게요", WRITTEN_AT_UTC));

    mockMvc
        .perform(
            post("/api/v1/posts/1/comments")
                .header(CommentController.REQUESTER_HEADER, "7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"연락처 남길게요\",\"parentId\":null,\"secret\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(12))
        .andExpect(jsonPath("$.parentId").value((Object) null))
        .andExpect(jsonPath("$.secret").value(true))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.content").value("연락처 남길게요"))
        .andExpect(jsonPath("$.createdAt").value("2026-09-14T09:00:00+09:00"));
  }

  @DisplayName("작성 응답에는 작성자 블록과 액션 목록을 담지 않는다.")
  @Test
  void writeCommentOmitsViewFields() throws Exception {
    given(commentCommandService.write(any()))
        .willReturn(
            new WrittenComment(12L, 3L, false, CommentStatus.ACTIVE, "저도요", WRITTEN_AT_UTC));

    mockMvc
        .perform(
            post("/api/v1/posts/1/comments")
                .header(CommentController.REQUESTER_HEADER, "7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"저도요\",\"parentId\":3,\"secret\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parentId").value(3))
        .andExpect(jsonPath("$.author").doesNotExist())
        .andExpect(jsonPath("$.availableActions").doesNotExist())
        .andExpect(jsonPath("$.replies").doesNotExist());
  }

  @DisplayName("본문이 500자를 넘으면 작성할 수 없다.")
  @Test
  void writeComment_contentIsTooLong() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/posts/1/comments")
                .header(CommentController.REQUESTER_HEADER, "7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"" + "가".repeat(501) + "\",\"secret\":false}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        .andExpect(jsonPath("$.message").value("본문은 500자 이하여야 합니다."));
  }

  @DisplayName("본문이 비어 있으면 작성할 수 없다.")
  @Test
  void writeComment_contentIsBlank() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/posts/1/comments")
                .header(CommentController.REQUESTER_HEADER, "7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"   \",\"secret\":false}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  @DisplayName("요청자 헤더가 없으면 작성할 수 없다.")
  @Test
  void writeComment_hasNoRequesterHeader() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/posts/1/comments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"저 갈게요!\",\"secret\":false}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  @DisplayName("요청자 헤더가 숫자가 아니면 작성할 수 없다.")
  @Test
  void writeComment_hasBrokenRequesterHeader() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/posts/1/comments")
                .header(CommentController.REQUESTER_HEADER, "나야나")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"저 갈게요!\",\"secret\":false}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }
}
