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
}
