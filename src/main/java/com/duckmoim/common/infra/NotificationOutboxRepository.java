package com.duckmoim.common.infra;

import com.duckmoim.common.domain.NotificationOutbox;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * 아웃박스의 저장소 (NT-01).
 *
 * <p><b>{@code JpaRepository} 를 상속하지 않는다.</b> {@code AuditLogRepository} 와 같은 판단이다 — 상속하면 이 티켓이 쓰지
 * 않는 문이 전부 열린다. 여기서 여는 것은 {@link #save} 하나다.
 *
 * <p><b>선점이 없다.</b> {@link #findSendable} 에 {@code FOR UPDATE} 를 걸지 않았으므로 인스턴스가 둘이면 두 워커가 같은 행을 집을
 * 수 있다. 행 선점은 NT-04 가 넣는다 — 그때까지 중복 발송을 막는 것은 {@code notification} 표의 {@code outbox_id} 유니크 제약이고,
 * 집는 것까지는 겹칠 수 있다.
 */
public interface NotificationOutboxRepository extends Repository<NotificationOutbox, Long> {

  /** 보낼 알림 한 건을 덧붙인다. */
  NotificationOutbox save(NotificationOutbox outbox);

  /** 워커가 한 건을 처리하려고 다시 읽는다. 배치가 건네준 번호는 트랜잭션 밖에서 온 값이다. */
  Optional<NotificationOutbox> findById(Long id);

  /**
   * 지금 보낼 수 있는 건을 오래된 순으로 집는다 (NT-02).
   *
   * <p>{@code nextAttemptAt} 이 NULL 인 것은 한 번도 실패하지 않은 건이다 — 발행 직후부터 보낼 수 있어야 하므로 함께 잡는다 (NT-03).
   *
   * <p>정렬이 {@code id} 인 이유는 그것이 곧 발행 순서라서다. 인덱스도 {@code (status, next_attempt_at, id)} 로 그 순서를 따라
   * 두었다 (V802).
   *
   * <p><b>시도를 다 쓴 건을 빼는 조건이 함께 있다.</b> 그 건은 DLQ 로 옮겨지지만 (NT-03) 옮기기 전까지는 이 표에 남아 있고, 조건이 없으면 영원히 다시
   * 집힌다. 인덱스가 이 컬럼까지 덮지는 않는다 — 다 쓴 건은 곧 표에서 빠지므로 걸러낼 양이 적다.
   */
  @Query(
      """
      SELECT o FROM NotificationOutbox o
      WHERE o.status = com.duckmoim.common.domain.OutboxStatus.PENDING
        AND o.attempts < :maxAttempts
        AND (o.nextAttemptAt IS NULL OR o.nextAttemptAt <= :nowInUtc)
      ORDER BY o.id ASC
      """)
  List<NotificationOutbox> findSendable(LocalDateTime nowInUtc, int maxAttempts, Pageable pageable);
}
