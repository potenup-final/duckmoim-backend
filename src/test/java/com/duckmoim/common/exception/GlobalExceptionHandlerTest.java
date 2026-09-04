package com.duckmoim.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 예외를 실제 요청에서 발생시켜 봉투와 로그를 검증한다.
 *
 * <p>Spring 컨텍스트를 띄우지 않고 {@code standaloneSetup} 을 쓴다. 컴포넌트 스캔을 거치지 않는 것이 이유다 — 아래 {@link TestApi}
 * 참고.
 */
class GlobalExceptionHandlerTest {

  private MockMvc mockMvc;
  private Logger handlerLogger;
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new TestApi())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    logs = new ListAppender<>();
    logs.start();
    handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    handlerLogger.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    handlerLogger.detachAppender(logs);
  }

  @DisplayName("비즈니스 예외는 에러 코드가 정한 status · code · message 로 나간다.")
  @Test
  void handle_businessException() throws Exception {
    // when & then
    mockMvc
        .perform(get("/test/business"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("POST_ALREADY_CLOSED"))
        .andExpect(jsonPath("$.message").value("이미 마감된 모집글입니다."));
  }

  @DisplayName("요청 본문 검증에 실패하면 400 이고 어느 필드가 틀렸는지 메시지로 알려 준다.")
  @Test
  void handle_bodyValidationFailure() throws Exception {
    // when & then
    mockMvc
        .perform(post("/test/posts").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
        .andExpect(jsonPath("$.message").value("제목은 필수입니다."));
  }

  @DisplayName("본문 JSON 이 깨져 있으면 400 이고 파싱 오류를 응답에 담지 않는다.")
  @Test
  void handle_malformedJson() throws Exception {
    // when
    String body =
        mockMvc
            .perform(
                post("/test/posts").contentType(MediaType.APPLICATION_JSON).content("{\"title\":"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    // then
    assertThat(body).doesNotContain("JsonParseException", "end-of-input", "line: 1");
  }

  /**
   * 아래 넷은 원래 400 · 405 · 415 · 418 이 맞는 요청이다. 핸들러를 나열하지 않으면 캐치올로 떨어져 500 이 된다.
   *
   * <p>버그가 아니라 <b>합의된 동작</b>이라 여기 못 박아 둔다. 어느 하나를 제대로 된 4xx 로 내리기로 하면 그때
   * {@link GlobalExceptionHandler} 에 핸들러를 추가하고 이 테스트에서 그 줄을 빼면 된다.
   */
  @DisplayName("나열하지 않은 프로토콜 예외는 500 으로 나간다 — 합의된 동작이다.")
  @Test
  void handle_unmappedProtocolExceptions() throws Exception {
    // 경로 변수 타입 불일치 — 원래 400
    mockMvc
        .perform(get("/test/posts/abc"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

    // 필수 쿼리 파라미터 누락 — 원래 400
    mockMvc.perform(get("/test/search")).andExpect(status().isInternalServerError());

    // 지원하지 않는 메서드 — 원래 405
    mockMvc.perform(post("/test/business")).andExpect(status().isInternalServerError());

    // 지원하지 않는 Content-Type — 원래 415
    mockMvc
        .perform(post("/test/posts").contentType(MediaType.TEXT_PLAIN).content("제목"))
        .andExpect(status().isInternalServerError());

    // 자기 status 를 들고 온 예외 — 원래 418
    mockMvc.perform(get("/test/teapot")).andExpect(status().isInternalServerError());
  }

  @DisplayName("예상하지 못한 예외는 500 이고 내부 사정을 응답에 담지 않는다.")
  @Test
  void handle_unexpectedException() throws Exception {
    // when
    String body =
        mockMvc
            .perform(get("/test/unexpected"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    // then
    assertThat(body).doesNotContain("token=secret-value", "IllegalStateException", "duckmoim");
  }

  @DisplayName("비즈니스 예외는 WARN 으로 남는다.")
  @Test
  void handle_businessExceptionIsLoggedAsWarn() throws Exception {
    // when
    mockMvc.perform(get("/test/business"));

    // then
    assertThat(logs.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .contains("status=409", "code=POST_ALREADY_CLOSED");
              assertThat(event.getThrowableProxy()).isNull();
            });
  }

  @DisplayName("예상하지 못한 예외는 스택 트레이스와 함께 ERROR 로 남는다.")
  @Test
  void handle_unexpectedExceptionIsLoggedAsError() throws Exception {
    // when
    mockMvc.perform(get("/test/unexpected"));

    // then
    assertThat(logs.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.ERROR);
              assertThat(event.getFormattedMessage())
                  .contains("status=500", "code=INTERNAL_ERROR");
              assertThat(event.getThrowableProxy()).isNotNull();
            });
  }

  /**
   * 도메인 티켓에서 {@code post.domain.PostErrorCode} 로 생길 enum 을 대신한다. 지금은 도메인 코드가 한 줄도 없어서, 도메인 에러 코드가
   * 봉투까지 흐르는지를 이것으로 확인한다.
   */
  @Getter
  @RequiredArgsConstructor
  private enum TestErrorCode implements ErrorCode {
    POST_ALREADY_CLOSED(HttpStatus.CONFLICT, "이미 마감된 모집글입니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
      return name();
    }
  }

  private record CreateRequest(@NotBlank(message = "제목은 필수입니다.") String title) {}

  /**
   * 테스트 전용 컨트롤러.
   *
   * <p><b>static 으로 만들지 않는다.</b> 테스트 소스도 {@code com.duckmoim} 아래라서, static 이면 컴포넌트 스캔이 이 클래스를 빈으로
   * 올려 {@code @SpringBootTest} 컨텍스트에 가짜 엔드포인트가 생긴다. 하필 "없는 경로" 검증이 그 매핑에 걸린다. 중첩 내부 클래스는 독립적이지 않아
   * 스캔 후보에서 빠진다 ({@code ClassPathScanningCandidateComponentProvider} 가 {@code isIndependent()} 로
   * 걸러낸다).
   */
  @RestController
  @RequestMapping("/test")
  class TestApi {

    @GetMapping("/business")
    void business() {
      throw new BusinessException(TestErrorCode.POST_ALREADY_CLOSED);
    }

    @GetMapping("/unexpected")
    void unexpected() {
      throw new IllegalStateException("token=secret-value");
    }

    @GetMapping("/posts/{postId}")
    void findPost(@PathVariable Long postId) {}

    @PostMapping("/posts")
    void createPost(@RequestBody @Valid CreateRequest request) {}

    @GetMapping("/search")
    void search(@RequestParam String keyword) {}

    @GetMapping("/teapot")
    void teapot() {
      throw new ResponseStatusException(HttpStatus.I_AM_A_TEAPOT, "짧고 뚱뚱해요");
    }
  }
}
