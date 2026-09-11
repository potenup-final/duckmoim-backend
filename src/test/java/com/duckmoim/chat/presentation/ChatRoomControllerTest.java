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
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.service.ChatRoomDetailQueryService;
import com.duckmoim.chat.service.ChatRoomDetailView;
import com.duckmoim.chat.service.ChatRoomListQueryService;
import com.duckmoim.chat.service.ChatRoomMemberView;
import com.duckmoim.chat.service.ChatRoomSummaryView;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.domain.LastSeen;
import java.time.LocalDateTime;
import java.util.List;
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
 * 방 목록·상세의 HTTP 계약 (CH-05 · CH-06).
 *
 * <p>어떤 방이 나오는지·누가 멤버인지는 서비스 통합 테스트가 본다 ({@code ChatRoomListQueryServiceTest} · {@code
 * ChatRoomDetailQueryServiceTest}). 여기서는 <b>요청자가 그대로 서비스에 넘어가는지</b>와 <b>에러 코드가 상태로 옮겨지는지</b>만 본다.
 *
 * <p>등급 판정은 여기 없다 — {@code EndpointGradeTest} 의 권한 표가 지킨다.
 */
@WebMvcTest(ChatRoomController.class)
@ImportSecurity
class ChatRoomControllerTest {

  private static final long USER_ID = 7L;
  private static final long ROOM_ID = 3L;

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private ChatRoomListQueryService chatRoomListQueryService;
  @MockitoBean private ChatRoomDetailQueryService chatRoomDetailQueryService;

  @DisplayName("방 목록을 요청자 기준으로 조회해 그대로 반환한다.")
  @Test
  void getRooms() throws Exception {
    given(chatRoomListQueryService.findRooms(USER_ID))
        .willReturn(
            List.of(
                new ChatRoomSummaryView(
                    ROOM_ID, 1L, "픽스처 모집글", LocalDateTime.of(2026, 10, 1, 9, 0), 2)));

    mockMvc
        .perform(get("/api/v1/chat-rooms").headers(bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].roomId").value(ROOM_ID))
        .andExpect(jsonPath("$[0].memberCount").value(2));

    then(chatRoomListQueryService).should().findRooms(eq(USER_ID));
  }

  @DisplayName("방 상세를 조회해 모집글 요약·멤버·writable 을 반환한다.")
  @Test
  void getRoom() throws Exception {
    given(chatRoomDetailQueryService.findRoom(ROOM_ID, USER_ID))
        .willReturn(
            new ChatRoomDetailView(
                ROOM_ID,
                1L,
                "픽스처 모집글",
                LocalDateTime.of(2026, 10, 1, 9, 0),
                List.of(new ChatRoomMemberView(USER_ID, "덕후", null, LastSeen.TODAY)),
                true));

    mockMvc
        .perform(get("/api/v1/chat-rooms/{roomId}", ROOM_ID).headers(bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roomId").value(ROOM_ID))
        .andExpect(jsonPath("$.post.postId").value(1))
        .andExpect(jsonPath("$.members[0].nickname").value("덕후"))
        .andExpect(jsonPath("$.writable").value(true));

    then(chatRoomDetailQueryService).should().findRoom(eq(ROOM_ID), eq(USER_ID));
  }

  @DisplayName("방이 없거나 멤버가 아니면 그 에러 코드의 상태로 나간다.")
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"CHAT_ROOM_NOT_FOUND", "CHAT_ROOM_ACCESS_DENIED"})
  void getRoomRejected(String errorCodeName) throws Exception {
    ChatErrorCode errorCode = ChatErrorCode.valueOf(errorCodeName);
    willThrow(new BusinessException(errorCode))
        .given(chatRoomDetailQueryService)
        .findRoom(any(), any());

    mockMvc
        .perform(get("/api/v1/chat-rooms/{roomId}", ROOM_ID).headers(bearer()))
        .andExpect(status().is(errorCode.getStatus().value()))
        .andExpect(jsonPath("$.code").value(errorCode.name()));
  }

  private HttpHeaders bearer() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(new AuthUser(USER_ID, true, false)));
    return headers;
  }
}
