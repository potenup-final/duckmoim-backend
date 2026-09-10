package com.duckmoim.notification.infra;

import com.duckmoim.notification.domain.Notification;
import org.springframework.data.repository.Repository;

/**
 * 알림함의 저장소 (NT-02).
 *
 * <p><b>{@code JpaRepository} 를 상속하지 않는다.</b> {@code NotificationOutboxRepository} · {@code
 * AuditLogRepository} 와 같은 판단이다 — 상속하면 이 티켓이 쓰지 않는 문이 전부 열린다.
 *
 * <p>여는 것은 둘이다. {@link #existsByOutboxId} 가 있는 이유는 <b>유니크 제약만으로는 워커가 이어서 돌 수 없기</b> 때문이다 — 제약 위반이
 * 나면 그 트랜잭션이 롤백 표시가 붙어 같은 트랜잭션에서 상태를 바꿀 수 없다. 그래서 먼저 물어보고, 제약은 경쟁이 정말 붙었을 때의 뒷막이로 둔다 (도메인 5장의 이중
 * 방어와 같은 모양이다).
 *
 * <p>목록 조회는 여기 없다. 커서와 정렬을 정하는 쪽이 NT-08 이다.
 */
public interface NotificationRepository extends Repository<Notification, Long> {

  /** 알림 한 건을 만든다. */
  Notification save(Notification notification);

  /** 이 발행으로 이미 알림이 만들어졌는지. */
  boolean existsByOutboxId(Long outboxId);
}
