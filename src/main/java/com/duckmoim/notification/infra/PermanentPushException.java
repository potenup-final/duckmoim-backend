package com.duckmoim.notification.infra;

/**
 * 다시 보내도 같은 결과인 푸시 실패 (ADR 0010).
 *
 * <p>백오프를 건너뛰고 <b>바로 DLQ 로 간다</b> — 잘못된 VAPID 설정이나 깨진 페이로드는 NT-03 의 세 번을 다 써도 같은 실패다.
 *
 * <p><b>만료된 구독(410)은 여기 해당하지 않는다.</b> NT-14 가 「만료 응답이 오면 그 구독을 지운다」로 정했으므로 그것은 실패가 아니라 정상 처리다. 구독을
 * 지우고 조용히 끝낸다.
 *
 * <p>{@code BusinessException} 을 상속하지 않는다. 사용자 요청에 대한 응답이 아니라 <b>워커 안에서만 도는 실패</b>라 HTTP 상태로 옮겨질 일이
 * 없다.
 */
public class PermanentPushException extends RuntimeException {

  public PermanentPushException(String message) {
    super(message);
  }

  public PermanentPushException(String message, Throwable cause) {
    super(message, cause);
  }
}
