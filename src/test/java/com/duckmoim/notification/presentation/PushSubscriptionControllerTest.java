package com.duckmoim.notification.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.presentation.ImportSecurity;
import com.duckmoim.notification.service.PushSubscriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 구독 등록·해제의 HTTP 계약 (NT-12).
 *
 * <p><b>브라우저가 준 모양을 그대로 받는지</b>가 여기서 보는 전부다. 프론트가 값을 풀어 옮기는 단계가 없어야 옮기다 틀릴 자리도 없다.
 *
 * <p>등록이 쌓이지 않는 것과 남의 기기를 못 끊는 것은 {@code PushSubscriptionTest} 가 실제 DB 로 본다. 등급 판정은 {@code
 * EndpointGradeTest} 의 권한 표가 두 행으로 지킨다.
 */
@WebMvcTest(PushSubscriptionController.class)
@ImportSecurity
class PushSubscriptionControllerTest {

  private static final long ME = 7L;
  private static final String PATH = "/api/v1/push-subscriptions";
  private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/token";

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;

  @MockitoBean private PushSubscriptionService pushSubscriptionService;

  @DisplayName("브라우저가 준 구독을 그대로 보내면 저장된다.")
  @Test
  void register_takesBrowserShape() throws Exception {
    mockMvc
        .perform(
            post(PATH)
                .headers(bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "endpoint": "%s",
                      "keys": { "p256dh": "공개키", "auth": "인증비밀" }
                    }
                    """
                        .formatted(ENDPOINT)))
        .andExpect(status().isOk());

    then(pushSubscriptionService).should().register(ME, ENDPOINT, "공개키", "인증비밀");
  }

  /** 키가 없으면 본문을 암호화할 수 없어 <b>발송 시점에야</b> 터진다. 받는 자리에서 끊는다. */
  @DisplayName("암호화 키가 빠지면 400 이고 저장되지 않는다.")
  @Test
  void register_rejectsMissingKeys() throws Exception {
    mockMvc
        .perform(
            post(PATH)
                .headers(bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"endpoint": "%s"}
                    """
                        .formatted(ENDPOINT)))
        .andExpect(status().isBadRequest());

    then(pushSubscriptionService).should(never()).register(anyLong(), anyString(), any(), any());
  }

  /** 주소를 질의 문자열이 아니라 본문으로 받는다 — 기기를 특정하는 값이라 접근 로그에 남기지 않는다. */
  @DisplayName("해제는 주소를 본문으로 받는다.")
  @Test
  void unregister_takesEndpointInBody() throws Exception {
    mockMvc
        .perform(
            delete(PATH)
                .headers(bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"endpoint": "%s"}
                    """
                        .formatted(ENDPOINT)))
        .andExpect(status().isOk());

    then(pushSubscriptionService).should().unregister(ME, ENDPOINT);
  }

  private HttpHeaders bearer() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(new AuthUser(ME, true, false)));

    return headers;
  }
}
