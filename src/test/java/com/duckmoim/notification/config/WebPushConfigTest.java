package com.duckmoim.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.duckmoim.notification.infra.NoOpNotificationPushSender;
import com.duckmoim.notification.infra.NotificationPushSender;
import com.duckmoim.notification.infra.PushSubscriptionRepository;
import com.duckmoim.notification.infra.WebPushNotificationSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

/**
 * VAPID 키를 읽어 푸시를 켜고 끄는 규칙 (NT-13).
 *
 * <p><b>이 검사가 라이브러리 선택을 지킨다.</b> 처음 고른 {@code com.interaso:webpush} 는 개인키를 <b>부호 있는 정수로</b> 읽어서, 첫
 * 바이트의 최상위 비트가 선 키를 「범위 밖」으로 거절했다 — {@code npx web-push generate-vapid-keys} 가 만드는 키의 대략 절반이다. 실물로
 * 확인했다.
 *
 * <p><b>그 실패는 배포 전에 안 드러난다.</b> 키가 없으면 푸시만 꺼지도록 만들어 두었으므로 (그래야 시크릿 등록 전 머지가 배포를 막지 않는다) 잘못된 키도 조용히
 * 지나갈 수 있었다. 그래서 <b>양쪽 절반의 실제 키</b>로 여기서 검사한다.
 *
 * <p><b>스프링을 띄우지 않는다.</b> 보는 것이 「이 문자열로 서명기를 만들 수 있나」 하나라 컨텍스트가 필요 없고, {@code @SpringBootTest}
 * 컨텍스트를 하나 더 만들면 남의 테스트가 커넥션을 못 얻는다 (CLAUDE.md 「겪은 함정」).
 */
@DisplayName("웹 푸시 설정")
class WebPushConfigTest {

  private static final String SUBJECT = "mailto:ops@duckmoim.com";

  private static final String PUBLIC_KEY =
      "BKAkIEkIKw-HXWkEiz7ifp21YBpQ9pFzFy6_6j2TAwjssmzWop4jxAh6ge9ahG2WuvoaXN3W4nARgSLF0TLu8Yc";
  private static final String PRIVATE_KEY = "axwN3LMiBck37BsF_ZIj6EQIJvQ48SaevyEz4lYIHdk";

  /**
   * 실제 {@code npx} 가 만든 키 둘이다. <b>개인키 첫 바이트의 최상위 비트가 각각 1 과 0</b> 이라 두 절반을 모두 지난다.
   *
   * <p>시험 전용이고 운영에 쓰이지 않는다.
   */
  @DisplayName("npx 가 만든 키로 서명기를 만든다.")
  @ParameterizedTest(name = "개인키 최상위 비트 {2}")
  @CsvSource({
    "BHIa3rinO3Ao7_6_SU5bscg-zLLW8YZocKg_XdzVnlI3lxvnYDBVJDSXawc7NAm6zIkPMYYgnvGxFbKDJRoz18c,"
        + "gL8V-2cUiqL4w_inXKXKANfaNaGINEKLQHiDHUzJEbI,1",
    "BKAkIEkIKw-HXWkEiz7ifp21YBpQ9pFzFy6_6j2TAwjssmzWop4jxAh6ge9ahG2WuvoaXN3W4nARgSLF0TLu8Yc,"
        + "axwN3LMiBck37BsF_ZIj6EQIJvQ48SaevyEz4lYIHdk,0"
  })
  void notificationPushSender_readsEveryVapidKey(String publicKey, String privateKey, int highBit) {
    // when & then — 키를 못 읽으면 여기서 예외가 난다
    assertThatCode(() -> senderOf(publicKey, privateKey, SUBJECT)).doesNotThrowAnyException();
  }

  @DisplayName("키가 다 있으면 실제로 보내는 구현이 올라온다.")
  @Test
  void notificationPushSender_isRealWhenConfigured() throws Exception {
    // when
    NotificationPushSender sender = senderOf(PUBLIC_KEY, PRIVATE_KEY, SUBJECT);

    // then
    assertThat(sender).isInstanceOf(WebPushNotificationSender.class);
  }

  /**
   * 키가 없는 것은 사고가 아니라 정상 경로다.
   *
   * <p>배포의 필수 시크릿 목록에 VAPID 를 넣지 않았다 — 넣으면 시크릿을 등록하기 전에 머지되는 순간 배포 전체가 멈춘다.
   */
  @DisplayName("키가 없으면 푸시만 꺼지고 기동은 된다.")
  @Test
  void notificationPushSender_fallsBackWhenKeysAreMissing() throws Exception {
    // when
    NotificationPushSender sender = senderOf("", "", "");

    // then
    assertThat(sender).isInstanceOf(NoOpNotificationPushSender.class);
  }

  /**
   * <b>{@code subject} 도 함께 본다.</b> 그것만 빠지면 서명은 되는데 푸시 서비스가 400 으로 거절한다 — 기동은 멀쩡한데 발송만 전부 실패해서 원인을
   * 찾기 어렵다.
   */
  @DisplayName("연락처만 빠져도 푸시를 켜지 않는다.")
  @Test
  void notificationPushSender_requiresSubject() throws Exception {
    // when
    NotificationPushSender sender = senderOf(PUBLIC_KEY, PRIVATE_KEY, "");

    // then
    assertThat(sender).isInstanceOf(NoOpNotificationPushSender.class);
  }

  private static NotificationPushSender senderOf(
      String publicKey, String privateKey, String subject) throws Exception {

    return new WebPushConfig(publicKey, privateKey, subject)
        .notificationPushSender(Mockito.mock(PushSubscriptionRepository.class), new ObjectMapper());
  }
}
