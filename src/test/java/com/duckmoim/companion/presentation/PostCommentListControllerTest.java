package com.duckmoim.companion.presentation;

import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.presentation.ImportSecurity;
import com.duckmoim.companion.domain.Comment;
import com.duckmoim.companion.domain.CommentActionPolicy;
import com.duckmoim.companion.domain.CommentCursor;
import com.duckmoim.companion.domain.CommentVisibilityPolicy;
import com.duckmoim.companion.service.CommentCommandService;
import com.duckmoim.companion.service.CommentQueryService;
import com.duckmoim.companion.service.CommentSlice;
import com.duckmoim.companion.service.CommentView;
import com.duckmoim.identity.domain.LastSeen;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 목록 응답의 HTTP 계약 (CM-06 · CM-07 · CM-18 · CM-20).
 *
 * <p>정렬 · 커서 경계 · 자리표시자 거르기는 저장소와 서비스 통합 테스트가 본다. 여기서는 <b>서비스 결과가 JSON 으로 어떻게 나가는지</b>만 본다.
 *
 * <p>조립기와 판정기를 실물로 가져온다 — 목으로 세우면 본문 키가 사라지는지, 액션이 붙는지가 검증되지 않는다. 그 둘이 이 경로의 계약이다.
 *
 * <p><b>{@code Comment} 를 리플렉션으로 세운다.</b> 저장 없이는 id 와 createdAt 이 없는데, 이 슬라이스 테스트에는 DB 가 없다. 도메인에
 * 테스트용 생성 경로를 뚫는 대신 테스트 안에서만 값을 채운다.
 */
@WebMvcTest(PostCommentController.class)
@ImportSecurity
@Import({CommentItemAssembler.class, CommentVisibilityPolicy.class, CommentActionPolicy.class})
class PostCommentListControllerTest {

  private static final LocalDateTime WRITTEN_AT_UTC = LocalDateTime.of(2026, 9, 14, 0, 0);

  private static final long HOST_ID = 1L;
  private static final long AUTHOR_ID = 2L;
  private static final long STRANGER_ID = 4L;

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private CommentCommandService commentCommandService;
  @MockitoBean private CommentQueryService commentQueryService;

  @DisplayName("비회원도 공개 댓글 본문을 받는다.")
  @Test
  void getComments_asGuest() throws Exception {
    givenSlice(view(11L, AUTHOR_ID, false, "저 갈게요!", List.of()), null, false);

    mockMvc
        .perform(get("/api/v1/posts/1/comments"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].content").value("저 갈게요!"))
        .andExpect(jsonPath("$.items[0].author.nickname").value("댓글덕후"))
        .andExpect(jsonPath("$.items[0].author.lastSeen").value("WITHIN_WEEK"))
        .andExpect(jsonPath("$.items[0].createdAt").value("2026-09-14T09:00:00+09:00"))
        .andExpect(jsonPath("$.hasNext").value(false))
        .andExpect(jsonPath("$.nextCursor").value((Object) null));
  }

  /** CM-20 — 모든 비밀 댓글은 비회원에게 자리표시자다. */
  @DisplayName("비회원에게 비밀 댓글은 본문 키가 없고 액션도 없다.")
  @Test
  void getComments_secretAsGuest() throws Exception {
    givenSlice(view(11L, AUTHOR_ID, true, "연락처 남길게요", List.of()), null, false);

    mockMvc
        .perform(get("/api/v1/posts/1/comments"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].secret").value(true))
        .andExpect(jsonPath("$.items[0].content").doesNotExist())
        .andExpect(jsonPath("$.items[0].author.nickname").value("댓글덕후"))
        .andExpect(jsonPath("$.items[0].availableActions").isEmpty());
  }

  /** CM-18 의 검증 기준 — 본인 댓글에 신고 버튼이 뜨면 안 된다. */
  @DisplayName("본인 댓글에는 신고 버튼이 붙지 않는다.")
  @Test
  void getComments_hidesReportOnOwnComment() throws Exception {
    givenSlice(view(11L, AUTHOR_ID, false, "저 갈게요!", List.of()), null, false);

    mockMvc
        .perform(get("/api/v1/posts/1/comments").headers(bearer(AUTHOR_ID)))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[0].availableActions")
                .value(org.hamcrest.Matchers.hasItems("REPLY", "EDIT", "DELETE")))
        .andExpect(
            jsonPath("$.items[0].availableActions")
                .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("REPORT"))));
  }

  @DisplayName("남의 댓글에는 신고 버튼이 붙는다.")
  @Test
  void getComments_showsReportOnOthersComment() throws Exception {
    givenSlice(view(11L, AUTHOR_ID, false, "저 갈게요!", List.of()), null, false);

    mockMvc
        .perform(get("/api/v1/posts/1/comments").headers(bearer(STRANGER_ID)))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[0].availableActions").value(org.hamcrest.Matchers.hasItem("REPORT")));
  }

  /** 도메인 7.1 — 비밀 대댓글은 부모 댓글 작성자도 본문을 본다. */
  @DisplayName("비밀 대댓글의 본문이 부모 댓글 작성자에게 나간다.")
  @Test
  void getComments_secretReplyForParentAuthor() throws Exception {
    CommentView reply = view(12L, STRANGER_ID, true, "저도 연락처 남길게요", List.of());
    ReflectionTestUtils.setField(reply.comment(), "parentId", 11L);
    givenSlice(view(11L, AUTHOR_ID, false, "저 갈게요!", List.of(reply)), null, false);

    mockMvc
        .perform(get("/api/v1/posts/1/comments").headers(bearer(AUTHOR_ID)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].replies[0].content").value("저도 연락처 남길게요"))
        // 대댓글이라 REPLY 가 없고, 남의 댓글이라 REPORT 만 남는다 (CM-18).
        .andExpect(jsonPath("$.items[0].replies[0].availableActions").value(contains("REPORT")));
  }

  @DisplayName("다음 페이지가 있으면 커서를 함께 내린다.")
  @Test
  void getComments_hasNextPage() throws Exception {
    givenSlice(
        view(11L, AUTHOR_ID, false, "저 갈게요!", List.of()),
        new CommentCursor(WRITTEN_AT_UTC, 11L),
        true);

    mockMvc
        .perform(get("/api/v1/posts/1/comments"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasNext").value(true))
        .andExpect(jsonPath("$.nextCursor").isNotEmpty());
  }

  @DisplayName("판독할 수 없는 커서는 400 이다.")
  @Test
  void getComments_hasBrokenCursor() throws Exception {
    mockMvc
        .perform(get("/api/v1/posts/1/comments").param("cursor", "!!broken!!"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  private void givenSlice(CommentView root, CommentCursor nextCursor, boolean hasNext) {
    given(commentQueryService.findComments(any()))
        .willReturn(new CommentSlice(List.of(root), nextCursor, hasNext, HOST_ID));
  }

  private static CommentView view(
      long id, long authorId, boolean secret, String content, List<CommentView> replies) {

    Comment comment = Comment.root(1L, authorId, content, secret);
    ReflectionTestUtils.setField(comment, "id", id);
    ReflectionTestUtils.setField(comment, "createdAt", WRITTEN_AT_UTC);

    return new CommentView(comment, "댓글덕후", "/avatar/a2.webp", LastSeen.WITHIN_WEEK, replies);
  }

  private HttpHeaders bearer(long userId) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.issueAccessToken(new AuthUser(userId, true, false)));
    return headers;
  }
}
