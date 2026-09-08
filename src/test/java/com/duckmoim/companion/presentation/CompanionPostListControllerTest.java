package com.duckmoim.companion.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.presentation.ImportSecurity;
import com.duckmoim.companion.domain.ClosedReason;
import com.duckmoim.companion.domain.MeetPoint;
import com.duckmoim.companion.domain.PostCursor;
import com.duckmoim.companion.domain.PostListQuery;
import com.duckmoim.companion.domain.PostStatus;
import com.duckmoim.companion.service.CompanionPostCommandService;
import com.duckmoim.companion.service.CompanionPostQueryService;
import com.duckmoim.companion.service.PostSlice;
import com.duckmoim.companion.service.PostView;
import com.duckmoim.identity.domain.LastSeen;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 목록 응답의 HTTP 계약 (PO-08).
 *
 * <p>정렬 · 커서 경계 · 댓글 수 집계는 저장소와 서비스 통합 테스트가 본다. 여기서는 <b>서비스 결과가 JSON 으로 어떻게 나가는지</b>와 요청 파라미터가 어떻게
 * 조회 조건으로 옮겨지는지만 본다.
 *
 * <p><b>등급 판정은 여기서 보지 않는다.</b> {@code EndpointGradeTest} 의 권한 표가 {@code GET /api/v1/posts} 행으로 이미
 * 지킨다.
 */
@WebMvcTest(CompanionPostController.class)
@ImportSecurity
class CompanionPostListControllerTest {

  /** 저장은 UTC 다. 응답에서 KST 오프셋이 붙어 09:00 으로 나가야 한다. */
  private static final LocalDateTime MEET_AT_UTC = LocalDateTime.of(2026, 9, 14, 0, 0);

  private static final LocalDateTime CREATED_AT_UTC = LocalDateTime.of(2026, 8, 29, 12, 10);

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private CompanionPostCommandService companionPostCommandService;
  @MockitoBean private CompanionPostQueryService companionPostQueryService;

  @Captor private ArgumentCaptor<PostListQuery> query;

  @DisplayName("비인증 요청도 모집글 목록을 받는다.")
  @Test
  void getPosts_asGuest() throws Exception {
    givenSlice(view("에이티즈 팝업 오픈런 같이 하실 분", "혼자 가려니 막막해서요."), null, false);

    mockMvc
        .perform(get("/api/v1/posts"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].title").value("에이티즈 팝업 오픈런 같이 하실 분"))
        .andExpect(jsonPath("$.items[0].author.nickname").value("덕질하는오리"));
  }

  /** 카드에 두 줄만 보이는데 20건치 본문을 통째로 내리면 응답이 커진다 (API 설계 2-4). */
  @DisplayName("목록은 본문 대신 잘라낸 excerpt 를 싣는다.")
  @Test
  void getPosts_hasExcerpt() throws Exception {
    givenSlice(view("제목", "가".repeat(500)), null, false);

    mockMvc
        .perform(get("/api/v1/posts"))
        .andExpect(jsonPath("$.items[0].excerpt").value("가".repeat(100)))
        .andExpect(jsonPath("$.items[0].content").doesNotExist());
  }

  @DisplayName("만남시각은 KST 오프셋을 붙여 내린다.")
  @Test
  void getPosts_isKst() throws Exception {
    givenSlice(view("제목", null), null, false);

    mockMvc
        .perform(get("/api/v1/posts"))
        .andExpect(jsonPath("$.items[0].meetAt").value("2026-09-14T09:00:00+09:00"));
  }

  /** status 만 받으면 「모집 완료」와 「종료」를 구분할 수 없다 (화면 계약). */
  @DisplayName("closedReason 을 함께 내려 배지 문구를 구분한다.")
  @Test
  void getPosts_hasClosedReason() throws Exception {
    givenSlice(closedView(), null, false);

    mockMvc
        .perform(get("/api/v1/posts"))
        .andExpect(jsonPath("$.items[0].status").value("CLOSED"))
        .andExpect(jsonPath("$.items[0].closedReason").value("MEET_TIME_PASSED"));
  }

  /** null 이 될 수 있는 필드는 생략하지 않고 null 로 명시한다 (API 컨벤션 「필드 표기 규칙」). */
  @DisplayName("행사를 고르지 않은 글은 행사 필드가 null 로 나간다.")
  @Test
  void getPosts_hasNoEvent() throws Exception {
    givenSlice(view("제목", null), null, false);

    mockMvc
        .perform(get("/api/v1/posts"))
        .andExpect(jsonPath("$.items[0].eventId").isEmpty())
        .andExpect(jsonPath("$.items[0].eventTitle").isEmpty())
        .andExpect(jsonPath("$.items[0].eventImageUrl").isEmpty());
  }

  @DisplayName("다음 페이지가 있으면 커서를 불투명 문자열로 내린다.")
  @Test
  void getPosts_hasNext() throws Exception {
    givenSlice(view("제목", null), new PostCursor(MEET_AT_UTC, 12L), true);

    mockMvc
        .perform(get("/api/v1/posts"))
        .andExpect(jsonPath("$.hasNext").value(true))
        .andExpect(jsonPath("$.nextCursor").value(new PostCursor(MEET_AT_UTC, 12L).encode()));
  }

  @DisplayName("마지막 페이지는 nextCursor 가 null 이고 hasNext 가 false 다.")
  @Test
  void getPosts_isLastPage() throws Exception {
    givenSlice(view("제목", null), null, false);

    mockMvc
        .perform(get("/api/v1/posts"))
        .andExpect(jsonPath("$.nextCursor").isEmpty())
        .andExpect(jsonPath("$.hasNext").value(false));
  }

  @DisplayName("status 를 생략하면 거르지 않는다.")
  @Test
  void getPosts_hasNoStatus() throws Exception {
    givenSlice(view("제목", null), null, false);

    mockMvc.perform(get("/api/v1/posts")).andExpect(status().isOk());

    assertThat(capturedQuery().status()).isNull();
  }

  @DisplayName("status=OPEN 은 모집중만 거르는 조건으로 넘어간다.")
  @Test
  void getPosts_isOpenOnly() throws Exception {
    givenSlice(view("제목", null), null, false);

    mockMvc.perform(get("/api/v1/posts").param("status", "OPEN")).andExpect(status().isOk());

    assertThat(capturedQuery().status()).isEqualTo(PostStatus.OPEN);
  }

  /** CLOSED 만 보는 화면이 없어 값을 늘리지 않았다 (API 설계 2-4). enum 으로 받으면 조용히 통과한다. */
  @DisplayName("status 에 OPEN 아닌 값을 주면 INVALID_INPUT 400 이다.")
  @Test
  void getPosts_hasUnknownStatus() throws Exception {
    mockMvc
        .perform(get("/api/v1/posts").param("status", "CLOSED"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  @DisplayName("판독할 수 없는 커서는 INVALID_INPUT 400 이다.")
  @Test
  void getPosts_hasBrokenCursor() throws Exception {
    mockMvc
        .perform(get("/api/v1/posts").param("cursor", "!!broken!!"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  @DisplayName("커서를 주면 판독해서 조회 조건으로 넘긴다.")
  @Test
  void getPosts_hasCursor() throws Exception {
    givenSlice(view("제목", null), null, false);
    String cursor = new PostCursor(MEET_AT_UTC, 12L).encode();

    mockMvc.perform(get("/api/v1/posts").param("cursor", cursor)).andExpect(status().isOk());

    assertThat(capturedQuery().cursor()).isEqualTo(new PostCursor(MEET_AT_UTC, 12L));
  }

  @DisplayName("size 를 생략하면 기본 크기로 조회한다.")
  @Test
  void getPosts_hasNoSize() throws Exception {
    givenSlice(view("제목", null), null, false);

    mockMvc.perform(get("/api/v1/posts")).andExpect(status().isOk());

    assertThat(capturedQuery().size()).isEqualTo(PostListQuery.DEFAULT_SIZE);
  }

  private PostListQuery capturedQuery() {
    then(companionPostQueryService).should().findPosts(query.capture());

    return query.getValue();
  }

  private void givenSlice(PostView view, PostCursor nextCursor, boolean hasNext) {
    given(companionPostQueryService.findPosts(any()))
        .willReturn(new PostSlice(List.of(view), nextCursor, hasNext));
  }

  private static PostView view(String title, String content) {
    return new PostView(
        1L,
        null,
        null,
        null,
        title,
        content,
        PostStatus.OPEN,
        null,
        4,
        MEET_AT_UTC,
        CREATED_AT_UTC,
        MeetPoint.of("더현대 서울 지하 1층", new BigDecimal("37.5256381"), new BigDecimal("126.9289384")),
        7L,
        "덕질하는오리",
        "/avatar/a1.webp",
        LastSeen.WITHIN_WEEK,
        5L);
  }

  private static PostView closedView() {
    PostView open = view("제목", null);

    return new PostView(
        open.id(),
        open.eventId(),
        open.eventTitle(),
        open.eventImageUrl(),
        open.title(),
        open.content(),
        PostStatus.CLOSED,
        ClosedReason.MEET_TIME_PASSED,
        open.capacity(),
        open.meetAt(),
        open.createdAt(),
        open.meetPoint(),
        open.hostId(),
        open.nickname(),
        open.profileImageUrl(),
        open.lastSeen(),
        open.commentCount());
  }
}
