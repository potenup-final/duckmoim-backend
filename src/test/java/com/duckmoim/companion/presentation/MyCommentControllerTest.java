package com.duckmoim.companion.presentation;

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
import com.duckmoim.companion.domain.CommentVisibilityPolicy;
import com.duckmoim.companion.domain.MyCommentCursor;
import com.duckmoim.companion.service.MyCommentQueryService;
import com.duckmoim.companion.service.MyCommentSlice;
import com.duckmoim.companion.service.MyCommentView;
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
 * 내 댓글 내역의 HTTP 계약 (CM-16 · AU-10).
 *
 * <p>정렬 · 필터 · 커서 경계는 저장소와 서비스 통합 테스트가 본다. 여기서는 <b>서비스 결과가 JSON 으로 어떻게 나가는지</b>만 본다.
 *
 * <p>조립기와 판정기를 실물로 가져온다 — 목으로 세우면 <b>내 비밀 댓글에 본문이 실리는지</b>가 검증되지 않는다. 그것이 CM-16 의 검증 기준이다.
 *
 * <p><b>{@code Comment} 를 리플렉션으로 세운다.</b> 저장 없이는 id 와 createdAt 이 없는데 이 슬라이스 테스트에는 DB 가 없다. {@code
 * PostCommentListControllerTest} 와 같은 방식이다.
 */
@WebMvcTest(MyCommentController.class)
@ImportSecurity
@Import({CommentItemAssembler.class, CommentVisibilityPolicy.class, CommentActionPolicy.class})
class MyCommentControllerTest {

  private static final LocalDateTime WRITTEN_AT_UTC = LocalDateTime.of(2026, 8, 30, 0, 40);

  private static final long ME = 2L;
  private static final long POST_ID = 1L;

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private MyCommentQueryService myCommentQueryService;

  /** CM-16 의 검증 기준 — 「비밀 댓글 포함 조회」. 내가 쓴 것이라 권한이 있다. */
  @DisplayName("내 비밀 댓글도 본문이 함께 온다.")
  @Test
  void getMyComments_carriesSecretContent() throws Exception {
    givenSlice(mine(31L, true, "카톡 아이디 night_ticket 입니다"), null, false);

    mockMvc
        .perform(get("/api/v1/users/me/comments").headers(bearer(ME)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(31))
        .andExpect(jsonPath("$.items[0].secret").value(true))
        .andExpect(jsonPath("$.items[0].content").value("카톡 아이디 night_ticket 입니다"))
        .andExpect(jsonPath("$.hasNext").value(false))
        .andExpect(jsonPath("$.nextCursor").value((Object) null));
  }

  /** CM-16 이 요구하는 항목 셋 — 모집글 제목 · 본문 · 작성 시각. */
  @DisplayName("내 댓글에는 모집글과 작성 시각이 함께 온다.")
  @Test
  void getMyComments_carriesPostAndTime() throws Exception {
    givenSlice(mine(31L, false, "저 갈게요!"), null, false);

    mockMvc
        .perform(get("/api/v1/users/me/comments").headers(bearer(ME)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].postId").value(POST_ID))
        .andExpect(jsonPath("$.items[0].postTitle").value("에이티즈 팝업 오픈런 같이 하실 분"))
        .andExpect(jsonPath("$.items[0].createdAt").value("2026-08-30T09:40:00+09:00"));
  }

  /** 화면 계약이 미결로 남긴 필드다. CM-16 의 요구 항목 밖이라 넣지 않았다 (MyCommentItemResponse). */
  @DisplayName("답글 여부와 상태는 응답에 담기지 않는다.")
  @Test
  void getMyComments_hasNoRepliedOrStatus() throws Exception {
    givenSlice(mine(31L, false, "저 갈게요!"), null, false);

    mockMvc
        .perform(get("/api/v1/users/me/comments").headers(bearer(ME)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].replied").doesNotExist())
        .andExpect(jsonPath("$.items[0].status").doesNotExist());
  }

  @DisplayName("다음 페이지가 있으면 커서를 함께 내린다.")
  @Test
  void getMyComments_hasNextPage() throws Exception {
    givenSlice(mine(31L, false, "저 갈게요!"), new MyCommentCursor(WRITTEN_AT_UTC, 31L), true);

    mockMvc
        .perform(get("/api/v1/users/me/comments").headers(bearer(ME)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasNext").value(true))
        .andExpect(jsonPath("$.nextCursor").isNotEmpty());
  }

  @DisplayName("판독할 수 없는 커서는 400 이다.")
  @Test
  void getMyComments_hasBrokenCursor() throws Exception {
    mockMvc
        .perform(get("/api/v1/users/me/comments").headers(bearer(ME)).param("cursor", "!!broken!!"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  /** 등급 판정 자체는 {@code EndpointGradeTest} 의 권한 표가 지킨다. 여기서는 관문이 실제로 걸리는지만 본다. */
  @DisplayName("토큰 없이 부르면 401 이다.")
  @Test
  void getMyComments_isAnonymous() throws Exception {
    mockMvc.perform(get("/api/v1/users/me/comments")).andExpect(status().isUnauthorized());
  }

  private void givenSlice(MyCommentView item, MyCommentCursor nextCursor, boolean hasNext) {
    given(myCommentQueryService.findMyComments(any()))
        .willReturn(new MyCommentSlice(List.of(item), nextCursor, hasNext));
  }

  private static MyCommentView mine(long id, boolean secret, String content) {
    Comment comment = Comment.root(POST_ID, ME, content, secret);
    ReflectionTestUtils.setField(comment, "id", id);
    ReflectionTestUtils.setField(comment, "createdAt", WRITTEN_AT_UTC);

    return new MyCommentView(comment, "에이티즈 팝업 오픈런 같이 하실 분");
  }

  private HttpHeaders bearer(long userId) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.issueAccessToken(new AuthUser(userId, true, false)));
    return headers;
  }
}
