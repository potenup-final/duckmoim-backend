package com.duckmoim.notification.infra;

import com.duckmoim.notification.domain.NotificationDelivery;

/**
 * 푸시를 보내지 않는다 (ADR 0010 · NT-13).
 *
 * <p><b>뜻이 바뀌었다.</b> STAR-131 이 이것을 둔 이유는 「아직 채널이 하나다」였고, 그때는 STAR-132 가 지울 파일이었다. 지금은 <b>VAPID 키가
 * 없을 때의 구현</b>이다 — 키를 배포의 필수 시크릿으로 두지 않기로 해서 (그러면 시크릿 등록 전에 머지되는 순간 배포가 멈춘다) 키 없는 기동이 정상 경로가 됐다.
 *
 * <p><b>어느 쪽이 뜨는지는 {@code WebPushConfig} 한 곳이 정한다.</b> 여기에 {@code @Component} 를 두지 않는 이유다 — 두면 실제
 * 구현과 빈이 둘이 되어 주입이 갈리지 않는다.
 */
public class NoOpNotificationPushSender implements NotificationPushSender {

  @Override
  public void send(NotificationDelivery delivery) {
    // 보낼 키가 없다. 실패가 아니라 「푸시를 켜지 않은 상태」다 — 인앱 알림은 그대로 남는다.
  }
}
