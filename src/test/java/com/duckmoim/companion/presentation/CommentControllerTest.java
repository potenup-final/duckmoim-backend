package com.duckmoim.companion.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.presentation.ImportSecurity;
import com.duckmoim.companion.domain.CommentStatus;
import com.duckmoim.companion.service.CommentCommandService;
import com.duckmoim.companion.service.CommentWriteCommand;
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
 * HTTP 계약만 본다 (테스트 컨벤션 · 테스트 계층).
 *
 * <p>열린 글 판정 · 깊이 · 부모 확인은 서비스 통합 테스트가 본다. 여기서 다시 검증하면 같은 규칙을 두 곳에서 관리하게 된다.
 *
 * <p><b>등급 판정도 여기서 다시 보지 않는다.</b> 토큰 없음 401 · 가입 미완료 403 은 {@code EndpointGradeTest} 의 권한 표가 {@code
 * POST /api/v1/posts/1/comments} 행으로 이미 지킨다. 이 클래스가 보는 것은 그 관문을 통과한 뒤의 계약이다 — <b>토큰의 회원번호가 작성자로
 * 도착하는가.</b>
 *
 * <p>{@link ImportSecurity} 가 필요한 이유 — {@code @WebMvcTest} 는 {@code @Configuration} 을 안 집어서, 이것 없이는
 * 우리 설정 대신 Spring Boot 기본 보안이 걸려 모든 요청이 403 이 된다.
 */
@WebMvcTest(CommentController.class)
@ImportSecurity
class CommentControllerTest {

  /** 저장은 UTC 다. 응답에서 KST 오프셋이 붙어 09:00 으로 나가야 한다. */
  private static final LocalDateTime WRITTEN_AT_UTC = LocalDateTime.of(2026, 9, 14, 0, 0);

  private static final AuthUser REQUESTER = new AuthUser(7L, true, false);

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private CommentCommandService commentCommandService;

  @Captor private ArgumentCaptor<CommentWriteCommand> command;

  @DisplayName("댓글을 작성하면 200 과 작성된 댓글이 돌아온다.")
  @Test
  void writeComment() throws Exception {
    given(commentCommandService.write(any()))
        .willReturn(
            new WrittenComment(12L, null, true, CommentStatus.ACTIVE, "연락처 남길게요", WRITTEN_AT_UTC));

    mockMvc
        .perform(writeRequest("{\"content\":\"연락처 남길게요\",\"parentId\":null,\"secret\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(12))
        .andExpect(jsonPath("$.parentId").value((Object) null))
        .andExpect(jsonPath("$.secret").value(true))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.content").value("연락처 남길게요"))
        .andExpect(jsonPath("$.createdAt").value("2026-09-14T09:00:00+09:00"));
  }

  /**
   * 이 티켓이 인증 관문에서 받아 쓰는 것이 회원번호 하나다.
   *
   * <p>작성자를 요청 본문으로 받지 않는 것이 남의 이름으로 쓰는 것을 막는 유일한 장치라, 토큰의 회원번호가 커맨드까지 실려 가는지가 계약이다. 어긋나면 조용히 실패한다
   * — {@code @AuthenticationPrincipal} 은 타입이 안 맞으면 예외 없이 null 을 넣는다.
   */
  @DisplayName("토큰의 회원번호가 댓글 작성자로 넘어간다.")
  @Test
  void writeCommentCarriesRequester() throws Exception {
    given(commentCommandService.write(any()))
        .willReturn(
            new WrittenComment(12L, null, false, CommentStatus.ACTIVE, "저 갈게요!", WRITTEN_AT_UTC));

    mockMvc
        .perform(writeRequest("{\"content\":\"저 갈게요!\",\"secret\":false}"))
        .andExpect(status().isOk());

    then(commentCommandService).should().write(command.capture());
    assertThat(command.getValue().authorId()).isEqualTo(REQUESTER.userId());
    assertThat(command.getValue().postId()).isEqualTo(1L);
  }

  @DisplayName("작성 응답에는 작성자 블록과 액션 목록을 담지 않는다.")
  @Test
  void writeCommentOmitsViewFields() throws Exception {
    given(commentCommandService.write(any()))
        .willReturn(
            new WrittenComment(12L, 3L, false, CommentStatus.ACTIVE, "저도요", WRITTEN_AT_UTC));

    mockMvc
        .perform(writeRequest("{\"content\":\"저도요\",\"parentId\":3,\"secret\":false}"))
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
        .perform(writeRequest("{\"content\":\"" + "가".repeat(501) + "\",\"secret\":false}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        .andExpect(jsonPath("$.message").value("본문은 500자 이하여야 합니다."));
  }

  @DisplayName("본문이 비어 있으면 작성할 수 없다.")
  @Test
  void writeComment_contentIsBlank() throws Exception {
    mockMvc
        .perform(writeRequest("{\"content\":\"   \",\"secret\":false}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
  }

  private MockHttpServletRequestBuilder writeRequest(String body) {
    return post("/api/v1/posts/1/comments")
        .headers(bearer())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  /** 진짜 토큰을 발급해 붙인다. {@link ImportSecurity} 가 JwtProvider 까지 가져오므로 슬라이스에서도 된다. */
  private HttpHeaders bearer() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(REQUESTER));
    return headers;
  }
}
