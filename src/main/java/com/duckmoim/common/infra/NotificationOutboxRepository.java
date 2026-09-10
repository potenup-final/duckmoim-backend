package com.duckmoim.common.infra;

import com.duckmoim.common.domain.NotificationOutbox;
import org.springframework.data.repository.Repository;

/**
 * 아웃박스의 저장소 (NT-01).
 *
 * <p><b>{@code JpaRepository} 를 상속하지 않는다.</b> {@code AuditLogRepository} 와 같은 판단이다 — 상속하면 이 티켓이 쓰지
 * 않는 문이 전부 열린다. 여기서 여는 것은 {@link #save} 하나다.
 *
 * <p><b>조회와 선점 메서드가 없는 것은 아직 정해지지 않았기 때문이다.</b> 이 표를 읽는 유일한 질의가 「미발행 건을 집는다」(NT-02)이고, 인스턴스가 둘일 때
 * 같은 행을 둘이 집지 않게 하는 방법(NT-04)이 그 질의의 모양을 정한다. 지금 짐작으로 열어 두면 그 티켓이 지우거나 고쳐야 한다.
 */
public interface NotificationOutboxRepository extends Repository<NotificationOutbox, Long> {

  /** 보낼 알림 한 건을 덧붙인다. */
  NotificationOutbox save(NotificationOutbox outbox);
}
