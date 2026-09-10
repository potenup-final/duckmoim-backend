package com.duckmoim.common.domain;

/**
 * 아웃박스 행의 발송 상태.
 *
 * <p><b>값이 하나인 것은 의도다.</b> NT-01 의 발행은 INSERT 로 끝나고 상태를 바꾸는 주체는 워커다. 발송 성공·실패와 그 사이의 전이는 워커를 만드는
 * NT-02 와 재시도를 정하는 NT-03 이 함께 정한다.
 *
 * <p>도메인 문서의 상태 머신 목록에도 아웃박스가 없다. 거기 있는 것은 알림함의 {@code Notification}(안 읽음 → 읽음 → 파기)이고 이 표와 다른 것이다.
 */
public enum OutboxStatus {

  /** 아직 워커가 집어가지 않았다. */
  PENDING
}
