package com.duckmoim.notification.infra;

import com.duckmoim.notification.domain.Notification;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

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
 * <p><b>목록 조회는 커스텀 프래그먼트다</b> (NT-08). 커서 조건이 선택이라 파생 쿼리 메서드로 감당되지 않아 {@link
 * NotificationQueryRepository} 가 따로 있고, 여기서 함께 상속해 service 에 저장소 하나만 주입되게 한다.
 *
 * <p><b>NT-09 · NT-10 이 여는 셋은 전부 수신자 조건을 뗄 수 없게 생겼다.</b> {@code I-24}(알림은 수신자 본인에게만 조회된다)는 이중 방어가
 * 없어 조건 하나가 유일한 방어선인데, 이 티켓이 <b>알림에 쓰기 경로를 처음 연다</b> — 읽기에서만 지키던 방어선을 쓰기에도 세워야 한다. 그래서 조건을 인자로 받아
 * 「넘기면 걸리는」 모양이 아니라, 파생 쿼리는 메서드 이름에 · 벌크는 JPQL 문자열에 박아 둔다 ({@code NotificationQueryRepositoryImpl}
 * 이 같은 이유로 {@code WHERE} 절을 상수에 박은 것과 같다).
 */
public interface NotificationRepository
    extends Repository<Notification, Long>, NotificationQueryRepository {

  /** 알림 한 건을 만든다. */
  Notification save(Notification notification);

  /** 이 발행으로 이미 알림이 만들어졌는지. */
  boolean existsByOutboxId(Long outboxId);

  /**
   * 읽음으로 바꿀 <b>내</b> 알림 한 건 (NT-09).
   *
   * <p><b>{@code findById} 를 두지 않는다.</b> 수신자까지 걸어야 남의 알림이 비어서 돌아오고, 그때 404 가 난다 (API-설계.md 「5. 결정
   * 사항」 D-14 ②). 번호만으로 찾는 문을 열어 두면 호출부가 그걸 쓰고 판정을 따로 붙이게 되는데, 그 판정은 빠뜨릴 수 있다.
   */
  Optional<Notification> findByIdAndRecipientId(Long id, Long recipientId);

  /**
   * 내 안 읽은 알림 건수 (NT-10). 배지에 그대로 나간다.
   *
   * <p>{@code V804} 의 인덱스가 {@code (recipient_id, created_at, id)} 라 {@code read_at} 은 인덱스로 걸러지지 않고
   * 행 조회가 붙는다. <b>그래도 인덱스를 더하지 않는다</b> — NT-11a 가 30일로 행 수를 묶는다. 재검토 기준은 한 수신자의 알림이 1,000건을 넘거나 이
   * 질의가 50ms 를 넘는 때다.
   */
  long countByRecipientIdAndReadAtIsNull(Long recipientId);

  /**
   * 내 안 읽은 알림을 <b>한 문장으로</b> 전부 읽음으로 바꾼다 (NT-09).
   *
   * <p><b>건건이 불러 고치지 않는 이유는 건수다.</b> NT-07 이 「메시지마다 알림을 만든다」로 정해져 있어 안 읽은 수가 크게 벌어질 수 있고, 그때 한 건씩
   * 고치면 그만큼 UPDATE 가 나간다.
   *
   * <p><b>{@code read_at is null} 이 도메인의 「덮어쓰지 않는다」와 같은 규칙이다.</b> 이미 읽은 행은 조건에서 빠져 처음 읽은 시각이 그대로
   * 남는다 ({@code Notification#markRead}).
   *
   * <p><b>{@code updated_at} 을 직접 채운다.</b> 벌크 JPQL 은 영속성 컨텍스트를 지나지 않아 {@code @PreUpdate} 가 돌지 않는다 —
   * 안 채우면 그 컬럼만 옛 값으로 남는다.
   *
   * <p>{@code clearAutomatically} 를 켠 것은 같은 트랜잭션에 이미 올라온 엔티티가 이 문장의 결과를 못 보기 때문이다. 켜 두면 이어지는 읽기가 DB
   * 를 다시 본다.
   *
   * <p><b>부르는 쪽에 트랜잭션이 있어야 한다</b> — service 의 {@code @Transactional} 안에서만 부른다.
   *
   * @return 실제로 바뀐 행 수
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "update Notification n set n.readAt = :now, n.updatedAt = :now"
          + " where n.recipientId = :recipientId"
          + " and n.readAt is null")
  int markAllRead(@Param("recipientId") Long recipientId, @Param("now") LocalDateTime now);
}
