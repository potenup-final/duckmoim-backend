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
import com.duckmoim.chat.service.ChatImageView;
import com.duckmoim.chat.service.ChatImageViewService;
import com.duckmoim.common.exception.BusinessException;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
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
  @DisplayName("물어본 메시지들의 열람 주소와 남은 수명을 반환한다.")
  @Test
  void getImages() throws Exception {
    given(chatImageViewService.viewUrlsOf(ROOM_ID, List.of(MESSAGE_ID), USER_ID))
        .willReturn(
            List.of(
                new ChatImageView(
                    MESSAGE_ID,
                    new SignedChatImageUrl("https://s3.example/get", Duration.ofSeconds(60)))));

    mockMvc
        .perform(
            get("/api/v1/chat-rooms/{roomId}/images", ROOM_ID)
                .param("messageIds", String.valueOf(MESSAGE_ID))
                .headers(bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].messageId").value(MESSAGE_ID))
        .andExpect(jsonPath("$[0].viewUrl").value("https://s3.example/get"))
        .andExpect(jsonPath("$[0].expiresInSeconds").value(60));

    then(chatImageViewService)
        .should()
        .viewUrlsOf(eq(ROOM_ID), eq(List.of(MESSAGE_ID)), eq(USER_ID));
  }

  /** 볼 수 없는 것이 빠져 빈 배열이 되는 것은 오류가 아니다 — 사진이 다 지워진 방을 여는 정상 경로다. */
  @DisplayName("볼 수 있는 사진이 없으면 빈 배열이다.")
  @Test
  void getImagesEmpty() throws Exception {
    given(chatImageViewService.viewUrlsOf(any(), any(), any())).willReturn(List.of());

    mockMvc
        .perform(
            get("/api/v1/chat-rooms/{roomId}/images", ROOM_ID)
                .param("messageIds", String.valueOf(MESSAGE_ID))
                .headers(bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  /**
   * <b>상한을 넘으면 조용히 자르지 않고 400 이다.</b>
   *
   * <p>잘린 사진은 화면에서 그냥 안 보이는 것이 되고, 클라이언트는 왜 안 보이는지 알 방법이 없다 — 목록의 {@code size} 를 조용히 맞추는 것과 갈리는
   * 자리다.
   */
  @DisplayName("메시지 번호를 51개 이상 물으면 400 이다.")
  @Test
  void getImagesRejectsTooManyIds() throws Exception {
    String[] tooMany =
        IntStream.rangeClosed(1, 51).mapToObj(String::valueOf).toArray(String[]::new);

    mockMvc
        .perform(
            get("/api/v1/chat-rooms/{roomId}/images", ROOM_ID)
                .param("messageIds", tooMany)
                .headers(bearer()))
        .andExpect(status().isBadRequest());
  }

  /**
   * 방 판정만 예외로 나간다 — 403 · 404.
   *
   * <p><b>메시지 쪽은 예외가 아니라 누락이다.</b> 지운 것도 다른 방의 것도 응답에서 빠질 뿐이라, 화면은 그 사진이 존재하는지 알 수 없다.
   */
  @DisplayName("방 판정에 걸리면 그 에러 코드의 상태로 나간다.")
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"CHAT_ROOM_ACCESS_DENIED", "CHAT_ROOM_NOT_FOUND"})
  void getImagesRejected(String errorCodeName) throws Exception {
    ChatErrorCode errorCode = ChatErrorCode.valueOf(errorCodeName);
    willThrow(new BusinessException(errorCode))
        .given(chatImageViewService)
        .viewUrlsOf(any(), any(), any());

    mockMvc
        .perform(
            get("/api/v1/chat-rooms/{roomId}/images", ROOM_ID)
                .param("messageIds", String.valueOf(MESSAGE_ID))
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
