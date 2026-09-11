package com.duckmoim.chat.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.presentation.ImportSecurity;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.service.ChatRoomInvitation;
import com.duckmoim.chat.service.ChatRoomInviteService;
import com.duckmoim.common.exception.BusinessException;
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
 * 초대의 HTTP 계약 (CH-02).
 *
 * <p>누구를 들일 수 있는지는 서비스 통합 테스트가 본다 ({@code ChatRoomInviteServiceTest}). 여기서는 <b>경로와 본문이 서비스로
 * 옮겨지는지</b>, 그리고 도메인이 낸 에러가 어떤 상태 코드로 나가는지만 본다.
 *
 * <p>등급 판정(익명 401 · 가입 미완료 403)은 여기 없다 — {@code EndpointGradeTest} 의 권한 표가 지킨다.
 */
@WebMvcTest(ChatRoomMemberController.class)
@ImportSecurity
class ChatRoomMemberControllerTest {

  private static final long POST_ID = 12L;
  private static final long ROOM_ID = 3L;
  private static final long HOST_ID = 7L;
  private static final long INVITEE_ID = 11L;

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private ChatRoomInviteService chatRoomInviteService;

  @DisplayName("초대에 성공하면 200 과 방 번호·멤버 수가 돌아온다.")
  @Test
  void invite() throws Exception {
    given(chatRoomInviteService.invite(any(), any(), any()))
        .willReturn(new ChatRoomInvitation(ROOM_ID, 2));

    mockMvc
        .perform(inviteRequest("{\"userId\": 11}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roomId").value(ROOM_ID))
        .andExpect(jsonPath("$.memberCount").value(2));
  }

  /** 요청자를 본문으로 받지 않는다. 토큰에서 나온 회원번호가 그대로 넘어가야 남의 이름으로 부를 수 없다. */
  @DisplayName("모집글 번호·초대 대상·요청자가 그대로 서비스에 넘어간다.")
  @Test
  void inviteCarriesArguments() throws Exception {
    given(chatRoomInviteService.invite(any(), any(), any()))
        .willReturn(new ChatRoomInvitation(ROOM_ID, 2));

    mockMvc.perform(inviteRequest("{\"userId\": 11}")).andExpect(status().isOk());

    then(chatRoomInviteService).should().invite(eq(POST_ID), eq(INVITEE_ID), eq(HOST_ID));
  }

  @DisplayName("초대 대상이 없으면 400 이고 서비스를 부르지 않는다.")
  @Test
  void inviteWithoutUserId() throws Exception {
    mockMvc.perform(inviteRequest("{}")).andExpect(status().isBadRequest());

    then(chatRoomInviteService).shouldHaveNoInteractions();
  }

  /** 코드마다 상태가 갈리는 것이 이 엔드포인트의 계약이다 — 403 · 400 · 409 · 404 넷이 서로 다른 화면을 부른다. */
  @DisplayName("서비스가 낸 채팅 에러 코드가 그 코드의 상태로 나간다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(ChatErrorCode.class)
  void inviteRejected(ChatErrorCode errorCode) throws Exception {
    willThrow(new BusinessException(errorCode))
        .given(chatRoomInviteService)
        .invite(any(), any(), any());

    mockMvc
        .perform(inviteRequest("{\"userId\": 11}"))
        .andExpect(status().is(errorCode.getStatus().value()))
        .andExpect(jsonPath("$.code").value(errorCode.name()));
  }

  private MockHttpServletRequestBuilder inviteRequest(String body) {
    return post("/api/v1/posts/{postId}/chat-room/members", POST_ID)
        .headers(bearer())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private HttpHeaders bearer() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(new AuthUser(HOST_ID, true, false)));
    return headers;
  }
}
