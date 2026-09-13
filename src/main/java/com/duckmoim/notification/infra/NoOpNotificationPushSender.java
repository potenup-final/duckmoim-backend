package com.duckmoim.notification.infra;

import com.duckmoim.notification.domain.NotificationDelivery;
import org.springframework.stereotype.Component;

/**
 * 아직 푸시를 보내지 않는다 (ADR 0010).
 *
 * <p><b>이 클래스가 「지금 채널이 하나다」를 코드로 말한다.</b> 인터페이스만 두고 구현을 비워 두면 빈이 없어 배치가 못 뜨고, 배치에 null 검사를 넣으면
 * <b>푸시가 붙은 뒤에도 그 분기가 남는다.</b>
 *
 * <p>STAR-132(NT-12 ~ NT-15)가 실제 구현으로 갈아끼운다. 그때 이 파일은 지워진다 — 대역이 아니라 <b>지금의 사실</b>이라 테스트가 아니라 운영
 * 코드에 둔다.
 */
@Component
public class NoOpNotificationPushSender implements NotificationPushSender {

  @Override
  public void send(NotificationDelivery delivery) {
    // 보낼 채널이 아직 없다. STAR-132 가 채운다.
  }
}
