package com.duckmoim.companion.presentation;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.presentation.ImportSecurity;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.domain.MeetPoint;
import com.duckmoim.companion.domain.PostStatus;
import com.duckmoim.companion.exception.PostErrorCode;
import com.duckmoim.companion.service.CompanionPostCommandService;
import com.duckmoim.companion.service.CompanionPostQueryService;
import com.duckmoim.companion.service.PostView;
import com.duckmoim.identity.domain.LastSeen;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 상세 응답의 HTTP 계약 (PO-11).
 *
 * <p><b>이 티켓의 검증 기준이 여기 있다</b> — <i>"비인증 요청에도 본문 포함 200"</i>. 나머지 필드는 목록 카드와 같은 조립을 지나고, 갈리는 것은
 * {@code content} 와 {@code createdAt} 둘이다.
 */
@WebMvcTest(CompanionPostController.class)
@ImportSecurity
class CompanionPostDetailControllerTest {

  private static final LocalDateTime MEET_AT_UTC = LocalDateTime.of(2026, 9, 14, 0, 0);
  private static final LocalDateTime CREATED_AT_UTC = LocalDateTime.of(2026, 8, 29, 12, 10);

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private CompanionPostCommandService companionPostCommandService;
  @MockitoBean private CompanionPostQueryService companionPostQueryService;

  @DisplayName("비인증 요청도 모집글 상세의 본문을 받는다.")
  @Test
  void getPost_asGuest() throws Exception {
    given(companionPostQueryService.findPost(1L)).willReturn(view("혼자 가려니 오픈런이 막막해서요."));

    mockMvc
        .perform(get("/api/v1/posts/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").value("혼자 가려니 오픈런이 막막해서요."))
        .andExpect(jsonPath("$.author.nickname").value("덕질하는오리"));
  }

  /** 목록에서 excerpt 가 content 로 바뀌고 셋이 늘어난다 (화면 계약). */
  @DisplayName("상세는 본문을 잘라 싣지 않는다.")
  @Test
  void getPost_hasFullContent() throws Exception {
    given(companionPostQueryService.findPost(1L)).willReturn(view("가".repeat(500)));

    mockMvc
        .perform(get("/api/v1/posts/1"))
        .andExpect(jsonPath("$.content").value("가".repeat(500)))
        .andExpect(jsonPath("$.excerpt").doesNotExist());
  }

  @DisplayName("작성시각과 만남시각을 KST 오프셋으로 내린다.")
  @Test
  void getPost_isKst() throws Exception {
    given(companionPostQueryService.findPost(1L)).willReturn(view(null));

    mockMvc
        .perform(get("/api/v1/posts/1"))
        .andExpect(jsonPath("$.meetAt").value("2026-09-14T09:00:00+09:00"))
        .andExpect(jsonPath("$.createdAt").value("2026-08-29T21:10:00+09:00"));
  }

  @DisplayName("댓글 수를 함께 내리고 댓글 본문은 담지 않는다.")
  @Test
  void getPost_hasCommentCount() throws Exception {
    given(companionPostQueryService.findPost(1L)).willReturn(view(null));

    mockMvc
        .perform(get("/api/v1/posts/1"))
        .andExpect(jsonPath("$.commentCount").value(6))
        .andExpect(jsonPath("$.comments").doesNotExist());
  }

  /** 마감은 meetAt 이 지나면 배치가 거는 것이고 별도 마감 시각이라는 개념이 설계에 없다 (화면 계약). */
  @DisplayName("상세에 마감 시각 필드를 두지 않는다.")
  @Test
  void getPost_hasNoClosesAt() throws Exception {
    given(companionPostQueryService.findPost(1L)).willReturn(view(null));

    mockMvc.perform(get("/api/v1/posts/1")).andExpect(jsonPath("$.closesAt").doesNotExist());
  }

  @DisplayName("없는 모집글은 POST_NOT_FOUND 404 다.")
  @Test
  void getPost_isMissing() throws Exception {
    willThrow(new BusinessException(PostErrorCode.POST_NOT_FOUND))
        .given(companionPostQueryService)
        .findPost(404L);

    mockMvc
        .perform(get("/api/v1/posts/404"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
  }

  private static PostView view(String content) {
    return new PostView(
        1L,
        "pg_8417",
        "에이티즈 X 애니티즈 팝업",
        "https://cdn.test/8417.webp",
        "에이티즈 팝업 오픈런 같이 하실 분",
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
        6L);
  }
}
