package com.duckmoim.chat.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.presentation.ImportSecurity;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.service.ChatRoomLeaveService;
import com.duckmoim.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 퇴장의 HTTP 계약 (CH-04).
 *
 * <p>누가 나갈 수 있는지는 {@code ChatRoomTest} 와 {@code ChatRoomLeaveServiceTest} 가 본다. 여기서는 <b>경로와 토큰의
 * 회원번호가 서비스로 옮겨지는지</b>, 그리고 도메인이 낸 에러가 어떤 상태 코드로 나가는지만 본다.
 *
 * <p>등급 판정(익명 401 · 가입 미완료 403)은 여기 없다 — {@code EndpointGradeTest} 의 권한 표가 지킨다. 제재 중에도 열린다는 것은 관문
 * 쪽이라 {@code SanctionGateTest} 가 본다.
 */
@WebMvcTest(ChatRoomLeaveController.class)
@ImportSecurity
class ChatRoomLeaveControllerTest {

  private static final long ROOM_ID = 3L;
  private static final long MEMBER_ID = 11L;

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private ChatRoomLeaveService chatRoomLeaveService;

  @DisplayName("나가기에 성공하면 본문 없이 200 이다.")
  @Test
  void leave() throws Exception {
    mockMvc.perform(leaveRequest()).andExpect(status().isOk()).andExpect(content().string(""));
  }

  /** 나가는 사람을 경로로 받지 않는다. 토큰에서 나온 회원번호가 그대로 넘어가야 남을 내보낼 수 없다. */
  @DisplayName("방 번호와 요청자가 그대로 서비스에 넘어간다.")
  @Test
  void leaveCarriesArguments() throws Exception {
    mockMvc.perform(leaveRequest()).andExpect(status().isOk());

    then(chatRoomLeaveService).should().leave(eq(ROOM_ID), eq(MEMBER_ID));
  }

  /** 방장의 409 와 비멤버의 403 이 서로 다른 화면을 부른다 — 앞은 안내, 뒤는 방 목록으로 돌려보낸다. */
  @DisplayName("서비스가 낸 채팅 에러 코드가 그 코드의 상태로 나간다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(ChatErrorCode.class)
  void leaveRejected(ChatErrorCode errorCode) throws Exception {
    willThrow(new BusinessException(errorCode)).given(chatRoomLeaveService).leave(any(), any());

    mockMvc
        .perform(leaveRequest())
        .andExpect(status().is(errorCode.getStatus().value()))
        .andExpect(jsonPath("$.code").value(errorCode.name()));
  }

  private MockHttpServletRequestBuilder leaveRequest() {
    return delete("/api/v1/chat-rooms/{roomId}/members/me", ROOM_ID).headers(bearer());
  }

  private HttpHeaders bearer() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(new AuthUser(MEMBER_ID, true, false)));
    return headers;
  }
}
