package com.duckmoim.notification.infra;

/**
 * 다시 해 보면 될 수도 있는 푸시 실패 (NT-03 · ADR 0010).
 *
 * <p><b>{@link PermanentPushException} 과 짝이지만 뜻이 다르다.</b> 그쪽은 「세 번 더 해도 같으니 바로 DLQ 로 보내라」는 신호이고,
 * 이쪽은 <b>신호가 아니라 운반 수단</b>이다 — 배치는 이미 「{@code PermanentPushException} 이 아닌 모든 예외」를 재시도로 다루므로, 이 타입이
 * 없어도 분류는 같다.
 *
 * <p><b>그런데도 두는 이유는 검사 예외 때문이다.</b> 푸시 라이브러리의 실패가 {@code Exception} 을 상속해서 {@link
 * NotificationPushSender#send} 의 시그니처를 그대로 지나가지 못한다. 이음매에 {@code throws} 를 더하면 <b>채널마다 다른 검사 예외가
 * 계약에 쌓이고</b>, 그러면 채널을 갈아 끼울 때 부르는 쪽이 함께 바뀐다 — 이음매를 둔 이유가 사라진다.
 *
 * <p>그래서 여기서 감싸고, ADR 0010 의 「되돌릴 수 있는 실패는 그냥 던진다」는 그대로 유지된다.
 */
public class TransientPushException extends RuntimeException {

  public TransientPushException(String message, Throwable cause) {
    super(message, cause);
  }
}
