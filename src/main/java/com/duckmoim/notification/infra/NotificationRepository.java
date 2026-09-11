package com.duckmoim.notification.infra;

import com.duckmoim.notification.domain.Notification;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
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
 * 「넘기면 걸리는」 모양이 아니라, 파생 쿼리는 메서드 이름에 · UPDATE 는 JPQL 문자열에 박아 둔다 ({@code
 * NotificationQueryRepositoryImpl} 이 같은 이유로 {@code WHERE} 절을 상수에 박은 것과 같다).
 */
public interface NotificationRepository
    extends Repository<Notification, Long>, NotificationQueryRepository {

  /** 알림 한 건을 만든다. */
  Notification save(Notification notification);

  /** 이 발행으로 이미 알림이 만들어졌는지. */
  boolean existsByOutboxId(Long outboxId);

  /**
   * 읽음으로 바꿀 <b>내</b> 알림이 있는지 (NT-09).
   *
   * <p><b>404 를 내기 위해서만 쓴다.</b> 수신자까지 걸어야 남의 알림이 없는 것과 똑같이 {@code false} 로 돌아온다 (API-설계.md 「5. 결정
   * 사항」 D-14 ②). 번호만으로 묻는 문을 열어 두면 호출부가 그걸 쓰고 판정을 따로 붙이게 되는데, 그 판정은 빠뜨릴 수 있다.
   *
   * <p><b>엔티티를 돌려주지 않는다.</b> 불러다 고치는 길이 막혀 있어 ({@link #markRead}) 여기서 필요한 것은 「있나 없나」 하나다.
   */
  boolean existsByIdAndRecipientId(Long id, Long recipientId);

  /**
   * 알림 하나를 읽음으로 바꾼다 (NT-09).
   *
   * <p><b>엔티티를 불러다 고치지 않는 이유는 동시성이다.</b> 불러온 {@code readAt} 은 조회 시점에 복사된 스냅숏이라 「이미 읽었나」를 메모리에서
   * 판정하면, 조회와 커밋 사이에 {@link #markAllRead} 가 지나갔을 때 그 판정이 그대로 통과해 <b>먼저 찍힌 시각을 덮어쓴다.</b> 실제로 재현된다
   * (PR #123 리뷰). 조건을 SQL 로 내리면 MySQL 이 잠근 현재 행에 대고 판정해서 그 창이 사라진다.
   *
   * <p><b>{@link #markAllRead} 와 글자 그대로 같은 가드다</b> — {@code read_at is null}. 개별과 전체가 같은 조건을 쓰는 것이
   * 「{@code read_at} 은 처음 읽은 시각을 지킨다」(API-설계.md 「2-10. 알림 (Notification) · 2차」)가 지켜지는 방식이다.
   *
   * <p><b>비관적 락을 쓰지 않은 이유</b> — {@code SELECT ... FOR UPDATE} 로도 막히지만, 없는 번호를 찌르면 REPEATABLE READ
   * 에서 갭 락이 잡혀 워커의 알림 INSERT 를 막을 수 있다. {@code I-25} 가 그 모양의 결합을 끊어 낸 자리라 (NT-04) 같은 표에 새 락을 들이지
   * 않는다.
   *
   * <p>0 이 돌아오는 것은 <b>실패가 아니다.</b> 이미 읽은 알림이라는 뜻이고, 그것도 성공이다.
   *
   * <p><b>부르는 쪽에 트랜잭션이 있어야 한다</b> — service 의 {@code @Transactional} 안에서만 부른다.
   *
   * @return 실제로 바뀐 행 수. 0 이면 이미 읽은 알림이다
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "update Notification n set n.readAt = :now, n.updatedAt = :now"
          + " where n.id = :id"
          + " and n.recipientId = :recipientId"
          + " and n.readAt is null")
  int markRead(
      @Param("id") Long id,
      @Param("recipientId") Long recipientId,
      @Param("now") LocalDateTime now);

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

  /**
   * 만료된 알림의 번호를 오래된 것부터 {@code chunk} 건까지 집는다 (NT-11a).
   *
   * <p><b>기준이 읽음이 아니라 생성 시각이다.</b> 읽지 않은 채로 30일이 지나도 파기된다 (도메인-모델링.md 「6. 상태 전이」).
   *
   * <p><b>{@code created_at} 으로 정렬하는 것은 성능이 아니라 안전이다.</b> {@code V805} 의 인덱스가 그 컬럼 하나이고, 두 인스턴스가
   * <b>같은 순서로</b> 읽어야 락 획득 순서가 같아져 데드락이 나지 않는다 — 이 배치는 중복 실행을 잠금으로 막지 않는다 (ADR 0009). 정렬을 빼면 실행 계획이
   * 바뀌는 날 그 근거가 조용히 사라진다.
   *
   * <p>번호만 읽는다. 지우는 데 필요한 것이 그것뿐이고, 엔티티를 띄우면 청크만큼 영속성 컨텍스트에 쌓인다.
   */
  @Query("select n.id from Notification n where n.createdAt < :cutoff order by n.createdAt")
  List<Long> findExpiredIds(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

  /**
   * 집어 둔 번호를 지운다 (NT-11a).
   *
   * <p><b>조건을 {@code created_at} 으로 다시 걸지 않는다.</b> 번호는 방금 그 조건으로 뽑은 것이고, 다시 걸면 같은 인덱스를 두 번 타면서 얻는
   * 것이 없다. 그 사이에 남이 먼저 지웠으면 그 행이 빠진 채로 돌아올 뿐이고, <b>그것도 맞는 결과다</b> — 지우는 일은 멱등이라 누가 지웠는지가 중요하지 않다.
   *
   * <p><b>부르는 쪽에 트랜잭션이 있어야 한다</b> — service 의 {@code @Transactional} 안에서만 부른다.
   *
   * @return 실제로 지워진 행 수. 집은 수보다 적을 수 있다
   */
  @Modifying(clearAutomatically = true)
  @Query("delete from Notification n where n.id in :ids")
  int deleteAllByIdIn(@Param("ids") List<Long> ids);
}
