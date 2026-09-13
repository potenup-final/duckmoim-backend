package com.duckmoim.chat.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.presentation.ImportSecurity;
import com.duckmoim.chat.domain.SignedChatImageUrl;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.service.ChatImageViewService;
import com.duckmoim.common.exception.BusinessException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 열람 주소 발급의 HTTP 계약 (CH-15).
 *
 * <p>누가 받을 수 있는지는 서비스 통합 테스트가 본다 ({@code ChatImageViewServiceTest}). 여기서는 <b>경로의 두 번호와 요청자가 그대로
 * 서비스에 넘어가는지</b>와 <b>에러 코드가 상태로 옮겨지는지</b>만 본다.
 *
 * <p>등급 판정은 여기 없다 — {@code EndpointGradeTest} 의 권한 표가 지킨다.
 */
@WebMvcTest(ChatImageViewController.class)
@ImportSecurity
class ChatImageViewControllerTest {

  private static final long USER_ID = 7L;
  private static final long ROOM_ID = 3L;
  private static final long MESSAGE_ID = 51L;

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private ChatImageViewService chatImageViewService;

  /** <b>바이트가 아니라 주소를 내린다.</b> 사진은 브라우저가 저장소에서 직접 받는다 — 업로드와 같은 구조다. */
  @DisplayName("열람 주소와 남은 수명을 반환한다.")
  @Test
  void getImage() throws Exception {
    given(chatImageViewService.viewUrlOf(ROOM_ID, MESSAGE_ID, USER_ID))
        .willReturn(new SignedChatImageUrl("https://s3.example/get", Duration.ofMinutes(4)));

    mockMvc
        .perform(
            get("/api/v1/chat-rooms/{roomId}/messages/{messageId}/image", ROOM_ID, MESSAGE_ID)
                .headers(bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.viewUrl").value("https://s3.example/get"))
        .andExpect(jsonPath("$.expiresInSeconds").value(240));

    then(chatImageViewService).should().viewUrlOf(eq(ROOM_ID), eq(MESSAGE_ID), eq(USER_ID));
  }

  /**
   * 세 코드가 그대로 상태로 옮겨진다 — 403 · 404 · 404.
   *
   * <p><b>「없다」와 「볼 수 없다」가 한 코드로 접힌다.</b> 지운 메시지도 다른 방의 메시지도 {@code CHAT_MESSAGE_NOT_FOUND} 라, 화면은 그
   * 사진이 존재하는지 알 수 없다.
   */
  @DisplayName("서비스가 거절하면 그 에러 코드의 상태로 나간다.")
  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {"CHAT_ROOM_ACCESS_DENIED", "CHAT_ROOM_NOT_FOUND", "CHAT_MESSAGE_NOT_FOUND"})
  void getImageRejected(String errorCodeName) throws Exception {
    ChatErrorCode errorCode = ChatErrorCode.valueOf(errorCodeName);
    willThrow(new BusinessException(errorCode))
        .given(chatImageViewService)
        .viewUrlOf(any(), any(), any());

    mockMvc
        .perform(
            get("/api/v1/chat-rooms/{roomId}/messages/{messageId}/image", ROOM_ID, MESSAGE_ID)
                .headers(bearer()))
        .andExpect(status().is(errorCode.getStatus().value()))
        .andExpect(jsonPath("$.code").value(errorCode.name()));
  }

  private HttpHeaders bearer() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(new AuthUser(USER_ID, true, false)));
    return headers;
  }
}
