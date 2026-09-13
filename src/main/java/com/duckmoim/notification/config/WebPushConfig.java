package com.duckmoim.notification.config;

import com.duckmoim.notification.infra.NoOpNotificationPushSender;
import com.duckmoim.notification.infra.NotificationPushSender;
import com.duckmoim.notification.infra.PushSubscriptionRepository;
import com.duckmoim.notification.infra.WebPushNotificationSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.GeneralSecurityException;
import java.security.Security;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.PushService;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 웹 푸시를 켜거나 끈다 (NT-13).
 *
 * <p><b>키가 없으면 푸시만 꺼진다.</b> 아무것도 보내지 않는 구현이 떠서 알림함 · 배지 · 수신 설정은 그대로 돈다. Redis 팬아웃과 채팅 이미지 저장소가 같은
 * 판단이고, 그래서 VAPID 는 배포의 필수 시크릿 목록에도 없다 — 필수로 두면 시크릿을 등록하기 전에 머지되는 순간 배포 전체가 멈춘다.
 *
 * <p><b>그 대가로 「푸시만 조용히 안 되는 정상 기동」이 생긴다.</b> 기동 로그 한 줄이 그때 드러나게 한다 — {@code ChatFanoutConfig} 가 팬아웃
 * 대상을 남기는 것과 같은 자리다.
 *
 * <p><b>빈을 하나만 만든다.</b> 둘을 각각 {@code @Component} 로 두면 주입할 때 어느 것인지 갈리지 않고, 조건 애너테이션으로 가르면 「어느 쪽이
 * 떴는지」가 두 파일에 흩어진다. 여기 한 메서드가 그 판단 전부다.
 */
@Slf4j
@Configuration
public class WebPushConfig {

  private final String publicKey;
  private final String privateKey;
  private final String subject;

  public WebPushConfig(
      @Value("${duckmoim.push.vapid.public-key}") String publicKey,
      @Value("${duckmoim.push.vapid.private-key}") String privateKey,
      @Value("${duckmoim.push.vapid.subject}") String subject) {

    this.publicKey = publicKey;
    this.privateKey = privateKey;
    this.subject = subject;
  }

  /**
   * 키가 다 있으면 실제로 보내는 구현을, 하나라도 없으면 아무것도 안 하는 구현을 올린다.
   *
   * <p><b>셋을 다 본다.</b> {@code subject} 가 빠지면 서명은 되지만 푸시 서비스가 400 으로 거절한다 — 기동은 멀쩡한데 발송만 전부 실패하는 상태가
   * 되어 원인을 찾기 어렵다.
   *
   * <p><b>키가 잘못된 모양이면 여기서 기동이 실패한다.</b> 발송 때까지 미루면 첫 알림이 나갈 때에야 드러나는데, 그때는 이미 아웃박스가 시도 횟수를 까먹고 있다.
   */
  @Bean
  public NotificationPushSender notificationPushSender(
      PushSubscriptionRepository pushSubscriptionRepository, ObjectMapper objectMapper)
      throws GeneralSecurityException {

    if (!configured()) {
      log.warn("[WebPushConfig.notificationPushSender] VAPID 키가 없어 웹 푸시를 끈다. 인앱은 그대로다.");

      return new NoOpNotificationPushSender();
    }

    registerBouncyCastle();
    log.info("[WebPushConfig.notificationPushSender] 웹 푸시 켜짐. subject={}", subject);

    return new WebPushNotificationSender(
        new PushService(publicKey, privateKey, subject), pushSubscriptionRepository, objectMapper);
  }

  /**
   * 푸시 라이브러리가 BouncyCastle 위에서 돈다.
   *
   * <p><b>등록하지 않으면 {@code NoSuchProviderException} 이 난다.</b> 그 의존성이 {@code compileOnly} 로 선언돼 있어 해석
   * 트리에도 안 보이고, 빠뜨리면 컴파일은 멀쩡한 채 발송만 터진다.
   *
   * <p>이미 있으면 다시 넣지 않는다 — 컨텍스트가 여럿 뜨는 검사에서 같은 프로바이더가 쌓이지 않게 한다.
   */
  private static void registerBouncyCastle() {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  /** 키를 로그에 남기지 않는다. 있는지 없는지만 본다. */
  private boolean configured() {
    return StringUtils.hasText(publicKey)
        && StringUtils.hasText(privateKey)
        && StringUtils.hasText(subject);
  }
}
