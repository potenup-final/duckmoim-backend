package com.duckmoim.chat.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.presentation.ImportSecurity;
import com.duckmoim.chat.domain.Message;
import com.duckmoim.chat.domain.MessageCursor;
import com.duckmoim.chat.domain.MessageStatus;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.service.ChatMessageDeleteService;
import com.duckmoim.chat.service.ChatMessageQueryService;
import com.duckmoim.chat.service.ChatMessageSendService;
import com.duckmoim.chat.service.MessageSlice;
import com.duckmoim.chat.service.MessageView;
import com.duckmoim.chat.service.SentMessage;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.domain.AuthorDisplay;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 메시지 경로의 HTTP 계약 (CH-07 · CH-08 · CH-09 · CH-12).
 *
 * <p>누가 보낼 수 있는지와 언제까지 보낼 수 있는지는 서비스 통합 테스트가 본다 ({@code ChatMessageSendServiceTest}). 여기서는 <b>경로와
 * 본문이 서비스로 옮겨지는지</b>, 그리고 도메인이 낸 에러가 어떤 상태 코드로 나가는지만 본다.
 *
 * <p>등급 판정(익명 401 · 가입 미완료 403)은 여기 없다 — {@code EndpointGradeTest} 의 권한 표가 지킨다.
 */
@WebMvcTest(ChatMessageController.class)
@ImportSecurity
@DisplayName("메시지 전송 계약")
class ChatMessageControllerTest {

  private static final long ROOM_ID = 3L;
  private static final long SENDER_ID = 7L;
  private static final long MESSAGE_ID = 51L;
  private static final String CLIENT_MESSAGE_ID = "9f1c2b7e-3a4d-4f10-9c2e-1b7d5a0e6c33";

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private ChatMessageSendService chatMessageSendService;
  @MockitoBean private ChatMessageQueryService chatMessageQueryService;
  @MockitoBean private ChatMessageDeleteService chatMessageDeleteService;

  /** 생성 성공도 200 이다 (API-설계.md 「성공 응답의 상태 코드」). 201 을 쓰지 않는다. */
  @DisplayName("전송에 성공하면 200 과 저장된 메시지가 돌아온다.")
  @Test
  void send() throws Exception {
    given(chatMessageSendService.send(any(), any(), any(), any())).willReturn(sentMessage());

    mockMvc
        .perform(sendRequest(body("8시에 3번 출구에서 봬요")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.messageId").value(MESSAGE_ID))
        .andExpect(jsonPath("$.roomId").value(ROOM_ID))
        .andExpect(jsonPath("$.senderId").value(SENDER_ID))
        .andExpect(jsonPath("$.content").value("8시에 3번 출구에서 봬요"));
  }

  /**
   * 저장은 UTC, 응답은 KST 다.
   *
   * <p>{@code BaseEntity} 가 {@code createdAt} 을 UTC 로 채우므로 그대로 내보내면 화면에 아홉 시간 전으로 찍힌다. 눈에 띄지 않는 종류의
   * 어긋남이라 계약에 못박는다.
   */
  @DisplayName("보낸 시각은 KST 오프셋으로 나간다.")
  @Test
  void sendReturnsKstTime() throws Exception {
    given(chatMessageSendService.send(any(), any(), any(), any())).willReturn(sentMessage());

    mockMvc
        .perform(sendRequest(body("8시에 봬요")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.createdAt").value("2026-10-02T20:10:00+09:00"));
  }

  /** 보낸 사람을 본문으로 받지 않는다. 토큰에서 나온 회원번호가 그대로 넘어가야 남의 이름으로 말할 수 없다. */
  @DisplayName("방 번호·식별자·본문·보낸 사람이 그대로 서비스에 넘어간다.")
  @Test
  void sendCarriesArguments() throws Exception {
    given(chatMessageSendService.send(any(), any(), any(), any())).willReturn(sentMessage());

    mockMvc.perform(sendRequest(body("8시에 봬요"))).andExpect(status().isOk());

    then(chatMessageSendService)
        .should()
        .send(eq(ROOM_ID), eq(SENDER_ID), eq(CLIENT_MESSAGE_ID), eq("8시에 봬요"));
  }

  @DisplayName("본문이 없으면 400 이고 서비스를 부르지 않는다.")
  @Test
  void sendWithoutContent() throws Exception {
    mockMvc
        .perform(sendRequest("{\"clientMessageId\": \"" + CLIENT_MESSAGE_ID + "\"}"))
        .andExpect(status().isBadRequest());

    then(chatMessageSendService).shouldHaveNoInteractions();
  }

  /** 식별자가 없으면 재시도를 알아볼 수 없다 (I-20). 서버가 만들어 주지 않는다 — 그러면 매 요청이 새 메시지가 된다. */
  @DisplayName("클라이언트 식별자가 없으면 400 이고 서비스를 부르지 않는다.")
  @Test
  void sendWithoutClientMessageId() throws Exception {
    mockMvc.perform(sendRequest("{\"content\": \"8시에 봬요\"}")).andExpect(status().isBadRequest());

    then(chatMessageSendService).shouldHaveNoInteractions();
  }

  /** 1000자는 상세가 정한 상한이고 {@code chat_message.content} 가 같은 값이다. 여기서 막지 않으면 DB 가 잘라 저장한다. */
  @DisplayName("본문이 1000자를 넘으면 400 이고 서비스를 부르지 않는다.")
  @Test
  void sendWithTooLongContent() throws Exception {
    String tooLong = "가".repeat(Message.MAX_CONTENT_LENGTH + 1);

    mockMvc.perform(sendRequest(body(tooLong))).andExpect(status().isBadRequest());

    then(chatMessageSendService).shouldHaveNoInteractions();
  }

  /** 코드마다 상태가 갈리는 것이 이 엔드포인트의 계약이다 — 403 · 409 · 404 가 서로 다른 화면을 부른다. */
  @DisplayName("서비스가 낸 채팅 에러 코드가 그 코드의 상태로 나간다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(ChatErrorCode.class)
  void sendRejected(ChatErrorCode errorCode) throws Exception {
    willThrow(new BusinessException(errorCode))
        .given(chatMessageSendService)
        .send(any(), any(), any(), any());

    mockMvc
        .perform(sendRequest(body("8시에 봬요")))
        .andExpect(status().is(errorCode.getStatus().value()))
        .andExpect(jsonPath("$.code").value(errorCode.getCode()));
  }

  @DisplayName("목록 조회는 200 과 항목·커서·다음 여부를 준다.")
  @Test
  void list() throws Exception {
    given(chatMessageQueryService.findMessages(any(), any()))
        .willReturn(new MessageSlice(List.of(activeView()), new MessageCursor(51L), true));

    mockMvc
        .perform(get("/api/v1/chat-rooms/{roomId}/messages", ROOM_ID).headers(authHeaders()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].messageId").value(MESSAGE_ID))
        .andExpect(jsonPath("$.items[0].sender.userId").value(SENDER_ID))
        .andExpect(jsonPath("$.items[0].sender.nickname").value("덕후1"))
        .andExpect(jsonPath("$.items[0].content").value("8시에 3번 출구에서 봬요"))
        .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
        .andExpect(jsonPath("$.hasNext").value(true))
        .andExpect(jsonPath("$.nextCursor").isNotEmpty());
  }

  /**
   * <b>{@code null} 이 아니라 키가 없어야 한다</b> (CM-08 · CH-12).
   *
   * <p>{@code null} 로 내리면 화면이 「빈 말풍선」과 「지운 말풍선」을 구분하지 못한다. API-설계.md 가 자리표시자를 <i>"본문만 빠지고 나머지는 그대로
   * 내려간다"</i> 로 정한 자리다.
   */
  @DisplayName("지운 메시지는 본문 키 자체가 빠진다.")
  @Test
  void list_omitsContentKeyForDeleted() throws Exception {
    given(chatMessageQueryService.findMessages(any(), any()))
        .willReturn(new MessageSlice(List.of(deletedView()), null, false));

    mockMvc
        .perform(get("/api/v1/chat-rooms/{roomId}/messages", ROOM_ID).headers(authHeaders()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].content").doesNotExist())
        .andExpect(jsonPath("$.items[0].status").value("DELETED"))
        .andExpect(jsonPath("$.items[0].sender.nickname").value("덕후1"))
        .andExpect(jsonPath("$.items[0].createdAt").isNotEmpty());
  }

  @DisplayName("마지막 페이지면 다음 커서가 null 이다.")
  @Test
  void list_lastPage() throws Exception {
    given(chatMessageQueryService.findMessages(any(), any()))
        .willReturn(new MessageSlice(List.of(activeView()), null, false));

    mockMvc
        .perform(get("/api/v1/chat-rooms/{roomId}/messages", ROOM_ID).headers(authHeaders()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hasNext").value(false))
        .andExpect(jsonPath("$.nextCursor").doesNotExist());
  }

  /** API-컨벤션.md 「Validation 규칙」 — 커서 디코딩 실패는 INVALID_INPUT 400 이다. */
  @DisplayName("판독할 수 없는 커서는 400 이고 서비스를 부르지 않는다.")
  @Test
  void list_rejectsMalformedCursor() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/chat-rooms/{roomId}/messages", ROOM_ID)
                .queryParam("cursor", "!!not-base64!!")
                .headers(authHeaders()))
        .andExpect(status().isBadRequest());

    then(chatMessageQueryService).shouldHaveNoInteractions();
  }

  /** 생성도 삭제도 200 이다 (API-설계.md 「성공 응답의 상태 코드」). 204 를 쓰지 않는다. */
  @DisplayName("삭제에 성공하면 본문 없는 200 이다.")
  @Test
  void deleteMessage() throws Exception {
    mockMvc
        .perform(
            delete("/api/v1/chat-rooms/{roomId}/messages/{messageId}", ROOM_ID, MESSAGE_ID)
                .headers(authHeaders()))
        .andExpect(status().isOk())
        .andExpect(content().string(""));

    then(chatMessageDeleteService).should().delete(ROOM_ID, MESSAGE_ID, SENDER_ID);
  }

  @DisplayName("삭제 서비스가 낸 채팅 에러 코드가 그 코드의 상태로 나간다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(ChatErrorCode.class)
  void deleteRejected(ChatErrorCode errorCode) throws Exception {
    willThrow(new BusinessException(errorCode))
        .given(chatMessageDeleteService)
        .delete(any(), any(), any());

    mockMvc
        .perform(
            delete("/api/v1/chat-rooms/{roomId}/messages/{messageId}", ROOM_ID, MESSAGE_ID)
                .headers(authHeaders()))
        .andExpect(status().is(errorCode.getStatus().value()))
        .andExpect(jsonPath("$.code").value(errorCode.getCode()));
  }

  private MessageView activeView() {
    return new MessageView(
        MESSAGE_ID,
        SENDER_ID,
        new AuthorDisplay("덕후1", null, null),
        "8시에 3번 출구에서 봬요",
        MessageStatus.ACTIVE,
        LocalDateTime.of(2026, 10, 2, 11, 10));
  }

  private MessageView deletedView() {
    return new MessageView(
        MESSAGE_ID,
        SENDER_ID,
        new AuthorDisplay("덕후1", null, null),
        null,
        MessageStatus.DELETED,
        LocalDateTime.of(2026, 10, 2, 11, 10));
  }

  private HttpHeaders authHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(new AuthUser(SENDER_ID, true, false)));

    return headers;
  }

  private SentMessage sentMessage() {
    return new SentMessage(
        MESSAGE_ID, ROOM_ID, SENDER_ID, "8시에 3번 출구에서 봬요", LocalDateTime.of(2026, 10, 2, 11, 10));
  }

  private String body(String content) {
    return "{\"clientMessageId\": \"%s\", \"content\": \"%s\"}"
        .formatted(CLIENT_MESSAGE_ID, content);
  }

  private MockHttpServletRequestBuilder sendRequest(String body) {
    return post("/api/v1/chat-rooms/{roomId}/messages", ROOM_ID)
        .headers(authHeaders())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }
}
