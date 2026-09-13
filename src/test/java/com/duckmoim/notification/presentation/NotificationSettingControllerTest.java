package com.duckmoim.notification.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.presentation.ImportSecurity;
import com.duckmoim.common.domain.NotificationKind;
import com.duckmoim.notification.service.NotificationSettingService;
import java.util.EnumSet;
import java.util.Set;
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

/**
 * 종류별 수신 설정의 HTTP 계약 (NT-11).
 *
 * <p><b>여기서 보는 것은 뒤집기다.</b> 저장은 「끈 종류」이고 화면은 「켜짐 셋」이라 두 번 뒤집힌다 — 그 변환이 어긋나면 사용자가 끈 것이 켜져서 돌아온다. 끄면
 * 알림이 안 쌓인다는 것은 {@code NotificationMuteTest} 가 본다.
 *
 * <p>등급 판정(익명 401 · 가입 미완료 403)은 {@code EndpointGradeTest} 의 권한 표가 두 행으로 지킨다.
 */
@WebMvcTest(NotificationSettingController.class)
@ImportSecurity
class NotificationSettingControllerTest {

  private static final long ME = 7L;
  private static final String PATH = "/api/v1/notifications/settings";

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private NotificationSettingService notificationSettingService;

  @Captor private ArgumentCaptor<Set<NotificationKind>> mutedKinds;

  /** 행이 없는 것이 곧 「전부 받는다」다 (V807). 가입 시점에 아무것도 만들지 않는 것이 그래서 가능하다. */
  @DisplayName("설정을 한 번도 만지지 않았으면 셋 다 켜짐으로 내려온다.")
  @Test
  void getMySettings_defaultsToEveryKindOn() throws Exception {
    given(notificationSettingService.findMutedKinds(ME))
        .willReturn(EnumSet.noneOf(NotificationKind.class));

    mockMvc
        .perform(get(PATH).headers(bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.postCommented").value(true))
        .andExpect(jsonPath("$.commentReplied").value(true))
        .andExpect(jsonPath("$.roomMessaged").value(true));
  }

  /** 끈 것만 내려주면 화면이 「없는 것 = 켜짐」을 조립해야 하고, 그 규칙이 두 곳에 생긴다. */
  @DisplayName("끈 종류만 꺼짐이고 나머지 둘은 함께 내려온다.")
  @Test
  void getMySettings_returnsEveryKind() throws Exception {
    given(notificationSettingService.findMutedKinds(ME))
        .willReturn(EnumSet.of(NotificationKind.ROOM_MESSAGED));

    mockMvc
        .perform(get(PATH).headers(bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roomMessaged").value(false))
        .andExpect(jsonPath("$.postCommented").value(true))
        .andExpect(jsonPath("$.commentReplied").value(true));
  }

  @DisplayName("꺼진 종류가 끈 종류로 뒤집혀 저장된다.")
  @Test
  void replaceMySettings_invertsToMutedKinds() throws Exception {
    mockMvc
        .perform(
            put(PATH)
                .headers(bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"postCommented": true, "commentReplied": false, "roomMessaged": false}
                    """))
        .andExpect(status().isOk());

    then(notificationSettingService).should().replaceMutedKinds(anyLong(), mutedKinds.capture());
    assertThat(mutedKinds.getValue())
        .containsExactlyInAnyOrder(
            NotificationKind.COMMENT_REPLIED, NotificationKind.ROOM_MESSAGED);
  }

  /**
   * <b>빠진 필드를 「꺼짐」으로 읽으면 안 된다.</b> 계약이 {@code PUT}(전체 수정)이라 빠진 것은 잘못 보낸 것이고, 조용히 채우면 화면이 안 만진 종류가
   * 꺼진다.
   */
  @DisplayName("종류 하나를 빼고 보내면 400 이고 아무것도 저장되지 않는다.")
  @Test
  void replaceMySettings_rejectsPartialBody() throws Exception {
    mockMvc
        .perform(
            put(PATH)
                .headers(bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"postCommented": true, "commentReplied": false}
                    """))
        .andExpect(status().isBadRequest());

    then(notificationSettingService).should(never()).replaceMutedKinds(anyLong(), any());
  }

  private HttpHeaders bearer() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(new AuthUser(ME, true, false)));

    return headers;
  }
}
