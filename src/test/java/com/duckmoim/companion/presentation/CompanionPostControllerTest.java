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
import com.duckmoim.companion.domain.MeetPoint;
import com.duckmoim.companion.domain.PostStatus;
import com.duckmoim.companion.service.CompanionPostCommandService;
import com.duckmoim.companion.service.CompanionPostWriteCommand;
import com.duckmoim.companion.service.WrittenCompanionPost;
import java.math.BigDecimal;
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
 * <p>행사 조회 · 만남시각 판정 · 정원 범위는 서비스 통합 테스트가 본다. 여기서 다시 검증하면 같은 규칙을 두 곳에서 관리하게 된다.
 *
 * <p><b>등급 판정도 여기서 다시 보지 않는다.</b> 토큰 없음 401 · 가입 미완료 403 은 {@code EndpointGradeTest} 의 권한 표가 {@code
 * POST /api/v1/posts} 행으로 이미 지킨다.
 */
@WebMvcTest(CompanionPostController.class)
@ImportSecurity
class CompanionPostControllerTest {

  /** 저장은 UTC 다. 응답에서 KST 오프셋이 붙어 09:00 으로 나가야 한다. */
  private static final LocalDateTime MEET_AT_UTC = LocalDateTime.of(2026, 9, 14, 0, 0);

  private static final AuthUser REQUESTER = new AuthUser(7L, true, false);

  private static final String MEET_POINT_JSON =
      "{\"place\":\"더현대 서울 지하 1층\",\"lat\":37.5256381,\"lng\":126.9289384}";

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private CompanionPostCommandService companionPostCommandService;

  @Captor private ArgumentCaptor<CompanionPostWriteCommand> command;

  @DisplayName("모집글을 작성하면 200 과 작성된 모집글이 돌아온다.")
  @Test
  void writePost() throws Exception {
    given(companionPostCommandService.create(any())).willReturn(written("pg_8417", 4));

    mockMvc
        .perform(
            writeRequest(
                """
                {"title":"에이티즈 팝업 오픈런 같이 하실 분","content":"혼자 가려니...",
                 "eventId":"pg_8417","capacity":4,"meetAt":"2026-09-14T09:00:00+09:00",
                 "meetPoint":%s}
                """
                    .formatted(MEET_POINT_JSON)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.eventId").value("pg_8417"))
        .andExpect(jsonPath("$.capacity").value(4))
        .andExpect(jsonPath("$.meetAt").value("2026-09-14T09:00:00+09:00"))
        .andExpect(jsonPath("$.meetPoint.place").value("더현대 서울 지하 1층"))
        .andExpect(jsonPath("$.meetPoint.lat").value(37.5256381));
  }

  @DisplayName("작성 응답에는 작성자 블록과 댓글 수를 담지 않는다.")
  @Test
  void writePostOmitsViewFields() throws Exception {
    given(companionPostCommandService.create(any())).willReturn(written(null, null));

    mockMvc
        .perform(writeRequest(requiredOnly()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.author").doesNotExist())
        .andExpect(jsonPath("$.commentCount").doesNotExist())
        .andExpect(jsonPath("$.closedReason").doesNotExist());
  }

  /**
   * 방장을 요청 본문으로 받지 않는 것이 남의 이름으로 쓰는 것을 막는 유일한 장치다.
   *
   * <p>어긋나면 조용히 실패한다 — {@code @AuthenticationPrincipal} 은 타입이 안 맞으면 예외 없이 null 을 넣는다.
   */
  @DisplayName("토큰의 회원번호가 모집글 방장으로 넘어간다.")
  @Test
  void writePostCarriesRequester() throws Exception {
    given(companionPostCommandService.create(any())).willReturn(written(null, null));

    mockMvc.perform(writeRequest(requiredOnly())).andExpect(status().isOk());

    then(companionPostCommandService).should().create(command.capture());
    assertThat(command.getValue().hostId()).isEqualTo(REQUESTER.userId());
  }

  @DisplayName("필수 셋만으로 모집글을 작성할 수 있다.")
  @Test
  void writePost_optionalFieldsAreAbsent() throws Exception {
    given(companionPostCommandService.create(any())).willReturn(written(null, null));

    mockMvc
        .perform(writeRequest(requiredOnly()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.eventId").value((Object) null))
        .andExpect(jsonPath("$.capacity").value((Object) null));
  }

  @DisplayName("제목이 40자를 넘으면 작성할 수 없다.")
  @Test
  void writePost_titleIsTooLong() throws Exception {
    mockMvc
        .perform(writeRequest(bodyWithTitle("가".repeat(41))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        .andExpect(jsonPath("$.message").value("제목은 40자 이하여야 합니다."));
  }

  @DisplayName("제목이 비어 있으면 작성할 수 없다.")
  @Test
  void writePost_titleIsBlank() throws Exception {
    mockMvc
        .perform(writeRequest(bodyWithTitle("   ")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        .andExpect(jsonPath("$.message").value("제목을 입력해 주세요."));
  }

  @DisplayName("본문이 500자를 넘으면 작성할 수 없다.")
  @Test
  void writePost_contentIsTooLong() throws Exception {
    mockMvc
        .perform(
            writeRequest(
                """
                {"title":"에이티즈 팝업 오픈런 같이 하실 분","content":"%s",
                 "meetAt":"2026-09-14T09:00:00+09:00","meetPoint":%s}
                """
                    .formatted("가".repeat(501), MEET_POINT_JSON)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("본문은 500자 이하여야 합니다."));
  }

  @DisplayName("만남시각 없이는 모집글을 작성할 수 없다.")
  @Test
  void writePost_meetAtIsMissing() throws Exception {
    mockMvc
        .perform(
            writeRequest(
                "{\"title\":\"에이티즈 팝업 오픈런 같이 하실 분\",\"meetPoint\":" + MEET_POINT_JSON + "}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("만남시각을 입력해 주세요."));
  }

  @DisplayName("만남지점 없이는 모집글을 작성할 수 없다.")
  @Test
  void writePost_meetPointIsMissing() throws Exception {
    mockMvc
        .perform(
            writeRequest(
                "{\"title\":\"에이티즈 팝업 오픈런 같이 하실 분\",\"meetAt\":\"2026-09-14T09:00:00+09:00\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("지도에서 만남 지점을 찍어 주세요."));
  }

  @DisplayName("좌표 없이는 모집글을 작성할 수 없다.")
  @Test
  void writePost_coordinateIsMissing() throws Exception {
    mockMvc
        .perform(
            writeRequest(
                """
                {"title":"에이티즈 팝업 오픈런 같이 하실 분","meetAt":"2026-09-14T09:00:00+09:00",
                 "meetPoint":{"place":"더현대 서울 지하 1층","lat":null,"lng":126.9289384}}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("지도에서 만남 지점을 찍어 주세요."));
  }

  private static String requiredOnly() {
    return bodyWithTitle("에이티즈 팝업 오픈런 같이 하실 분");
  }

  private static String bodyWithTitle(String title) {
    return """
        {"title":"%s","meetAt":"2026-09-14T09:00:00+09:00","meetPoint":%s}
        """
        .formatted(title, MEET_POINT_JSON);
  }

  private static WrittenCompanionPost written(String eventId, Integer capacity) {
    return new WrittenCompanionPost(
        1L,
        eventId,
        eventId == null ? null : "에이티즈 X 애니티즈 팝업",
        eventId == null ? null : "https://cdn.example.test/8417.webp",
        "에이티즈 팝업 오픈런 같이 하실 분",
        "혼자 가려니...",
        PostStatus.OPEN,
        capacity,
        MEET_AT_UTC,
        MeetPoint.of("더현대 서울 지하 1층", new BigDecimal("37.5256381"), new BigDecimal("126.9289384")),
        MEET_AT_UTC);
  }

  private MockHttpServletRequestBuilder writeRequest(String body) {
    return post("/api/v1/posts")
        .headers(bearer())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private HttpHeaders bearer() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.issueAccessToken(REQUESTER));
    return headers;
  }
}
