package com.duckmoim.notification.infra;

import com.duckmoim.notification.domain.NotificationOutboxDlq;
import org.springframework.data.repository.Repository;

/**
 * DLQ 의 저장소 (NT-03).
 *
 * <p>여는 것은 {@link #save} 하나다. 백오피스 조회는 엔드포인트와 응답 필드가 정해지는 티켓이 더한다 — 지금 짐작으로 열면 그 티켓이 고쳐야 한다.
 *
 * <p>지우는 문은 두지 않는다. 옮겨 온 건을 되돌리는 요구가 아직 없고 (재발송은 NT-03 범위 밖이다), 있으면 실수로 증거가 사라진다.
 */
public interface NotificationOutboxDlqRepository extends Repository<NotificationOutboxDlq, Long> {

  /** 못 보낸 건 하나를 옮겨 담는다. */
  NotificationOutboxDlq save(NotificationOutboxDlq dlq);

  /**
   * 포기한 건이 몇 개인지 (NT-05).
   *
   * <p><b>조회는 아직 못 열지만 세는 것은 연다.</b> 지금 DLQ 를 볼 수단이 이관 시점의 ERROR 로그 한 줄뿐이라, 운영 중 쌓여도 로그를 뒤지지 않으면
   * 모른다. 건수는 응답 필드를 정할 필요가 없어 짐작으로 여는 문이 아니다.
   */
  long count();
}
