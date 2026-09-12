package com.duckmoim.notification.infra;

import com.duckmoim.notification.domain.NotificationDelivery;

/**
 * 웹 푸시를 보내는 자리 (ADR 0010 의 T2).
 *
 * <p><b>지금은 비어 있다.</b> 실제 발송은 STAR-132(NT-12 ~ NT-15)가 채운다. 그런데도 자리를 먼저 내는 이유는 <b>검사</b>다 — 채널이 하나면
 * 「한 채널이 실패해도 다른 채널의 결과가 남는다」를 증명할 수 없다. 이음매가 있으면 실패하는 푸시를 주입해 인앱이 살아남는 것을 지금 본다 (0008 이 {@code
 * ClaimStrategy} 를 남긴 것과 같은 판단이다).
 *
 * <p><b>트랜잭션 밖에서 불린다.</b> 여기서 던진 예외는 T1 이 만든 인앱 알림을 되돌리지 않는다. 그것이 이 인터페이스가 존재하는 이유 전부다.
 *
 * <p><b>실패의 성격은 던지는 쪽이 안다.</b> 되돌릴 수 있는 실패는 그냥 던지고, 세 번 더 해도 같은 실패는 {@link PermanentPushException}
 * 으로 던진다 — 부르는 쪽이 예외를 뜯어 판정하게 하면 그 판정을 빠뜨릴 수 있다.
 */
public interface NotificationPushSender {

  /**
   * 한 건을 푸시로 보낸다.
   *
   * <p>보낼 구독이 없으면 <b>아무것도 하지 않는다.</b> 그것은 실패가 아니다 — 푸시를 켜지 않은 사용자에게도 인앱 알림은 남는다.
   */
  void send(NotificationDelivery delivery);
}
