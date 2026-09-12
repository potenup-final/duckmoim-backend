package com.duckmoim.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보낼 알림을 적어두는 자리 (NT-01).
 *
 * <p><b>애그리게이트가 아니다.</b> 자기 불변식도 라이프사이클도 없어 도메인-모델링.md 「3.1 경계와 트랜잭션 범위」 의 표에 없다. 댓글 작성과 메시지 전송의 부수
 * 효과로만 쌓인다 — {@code AuditLog} 와 같은 성격이다.
 *
 * <p><b>도메인 트랜잭션 안에서 하는 일은 이 행을 넣는 것뿐이다.</b> 발송은 워커가 트랜잭션 밖에서 한다 (NT-02). 그래서 알림 채널이 죽어 있어도 댓글 작성이
 * 실패하지 않고, 그 자리가 {@code I-25} 의 검증 위치다 (도메인-모델링.md 「5. 불변식」).
 *
 * <p><b>수신자를 넣는 쪽이 정한다.</b> 워커가 나중에 계산하려면 Companion 에 「이 댓글이 달린 모집글의 방장이 누구인가」 를 물어야 하는데,
 * 도메인-모델링.md 「2. 바운디드 컨텍스트」 의 화살표가 그것을 막는다 (Notification 은 Identity 만 안다). 그래서 알림에 필요한 값은 이 행이 전부
 * 들고 있고, 나중에 채울 수 있는 값이 없다.
 *
 * <p>수신자와 대상을 ID 로만 참조한다 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」). FK 도 걸지 않는다.
 */
@Entity
@Table(name = "notification_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationOutbox extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 알림을 받는 사람의 회원번호. */
  @Column(name = "recipient_id", nullable = false, updatable = false)
  private Long recipientId;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, updatable = false, length = 20)
  private NotificationKind kind;

  @Column(name = "post_id", nullable = false, updatable = false)
  private Long postId;

  @Column(name = "comment_id", nullable = false, updatable = false)
  private Long commentId;

  /**
   * 발송 상태.
   *
   * <p>「보낼 것」을 적은 컬럼들이 {@code updatable = false} 인 것은 그 값이 발행 시점에 정해지기 때문이다. 만든 뒤에 바뀌는 것은 발송의 진행 상태
   * 셋뿐이다 — 이 컬럼과 {@link #attempts} · {@link #nextAttemptAt} (NT-03 이 더했다).
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private OutboxStatus status;

  /** 발송을 몇 번 시도했는지 (NT-03). 세 번이면 DLQ 로 옮긴다. */
  @Column(name = "attempts", nullable = false)
  private int attempts;

  /**
   * 다음에 집어도 되는 시각 (NT-03). NULL 은 「지금 집어도 된다」다.
   *
   * <p>발행 시점에 NULL 인 것이 의도다 — 「한 번도 실패하지 않았다」와 「지금 보낼 때다」가 같은 뜻이라 값을 나눌 이유가 없다.
   */
  @Column(name = "next_attempt_at")
  private LocalDateTime nextAttemptAt;

  private NotificationOutbox(NotificationKind kind, Long recipientId, Long postId, Long commentId) {

    this.kind = kind;
    this.recipientId = recipientId;
    this.postId = postId;
    this.commentId = commentId;
    this.status = OutboxStatus.PENDING;
  }

  /**
   * 아직 보내지 않은 알림 한 건을 만든다.
   *
   * <p><b>상태를 인자로 받지 않는다.</b> 발행 시점의 상태는 늘 {@link OutboxStatus#PENDING} 이고, 그것을 바꾸는 것은 워커의 일이다 —
   * 받으면 「이미 보냈음」으로 적힌 행이 발행될 수 있다.
   *
   * <p><b>시각도 받지 않는다.</b> {@code AuditLog} 는 행위 시각이 도메인의 사실이라 인자로 받지만, 여기서 시각은 「언제 쌓였나」 뿐이라 {@link
   * BaseEntity} 의 {@code created_at} 으로 충분하다.
   */
  public static NotificationOutbox of(
      NotificationKind kind, Long recipientId, Long postId, Long commentId) {

    Objects.requireNonNull(kind, "아웃박스 행은 알림 종류를 가진다.");
    Objects.requireNonNull(recipientId, "아웃박스 행은 수신자를 가진다.");
    Objects.requireNonNull(postId, "아웃박스 행은 모집글을 가진다.");
    Objects.requireNonNull(commentId, "아웃박스 행은 댓글을 가진다.");

    return new NotificationOutbox(kind, recipientId, postId, commentId);
  }

  /** 아직 보내지 않은 건인지. 워커가 「내가 처리할 건인가」를 묻는 자리다 (NT-02). */
  public boolean isPending() {
    return status == OutboxStatus.PENDING;
  }

  /**
   * 보냈다고 적는다 (NT-02). 종착이다.
   *
   * <p><b>이미 보낸 건에는 부를 수 없다.</b> 이 가드는 프로그래밍 실수를 잡는 것이지 동시 실행을 잡는 것이 아니다 — 선점이 없는 동안 두 워커가 같은 건을 집는
   * 것은 정상으로 일어나고 (NT-04), 그 경우는 부르는 쪽이 {@link #isPending} 으로 먼저 걸러 낸다. 여기서 예외로 다루면 배치의 주기 전체가 끝난다.
   */
  public void markSent() {
    requirePending();

    this.status = OutboxStatus.SENT;
  }

  /**
   * 실패를 적고 다음 시도를 미룬다 (NT-03).
   *
   * <p>상태를 바꾸지 않는다 — 실패는 「아직 못 보냈다」의 한 형태다. 다음 시도 시각을 부르는 쪽이 주는 것은 백오프 간격이 설정값이라서다. 도메인이 그 값을 알면 배포
   * 없이 조절할 수 없다.
   */
  public void failed(LocalDateTime nextAttemptAt) {
    requirePending();
    Objects.requireNonNull(nextAttemptAt, "다음 시도 시각이 필요하다.");

    this.attempts += 1;
    this.nextAttemptAt = nextAttemptAt;
  }

  /**
   * 이 건을 내가 맡았다고 표시한다 (NT-04).
   *
   * <p><b>새 상태도 새 컬럼도 두지 않는다.</b> {@link #nextAttemptAt} 의 뜻이 이미 「이 시각 전에는 집지 마라」라서, 선점은 그 값을 리스
   * 만료로 미는 일이 된다. {@code CLAIMED} 상태를 새로 만들면 <b>선점한 워커가 죽었을 때 그 상태에서 빠져나올 길을 따로 만들어야 한다</b> — 리스는
   * 시각이라 저절로 풀린다.
   *
   * <p><b>{@link #attempts} 를 올리지 않는다.</b> 선점은 시도가 아니다. 올리면 성공한 발송도 재시도를 한 번 까먹어서 NT-03 의 「3회」가
   * 실제로는 2회가 된다.
   *
   * @param leaseUntil 이 시각까지는 남이 집지 않는다. 지나면 저절로 다시 집힌다
   */
  public void claim(LocalDateTime leaseUntil) {
    requirePending();
    Objects.requireNonNull(leaseUntil, "리스 만료 시각이 필요하다.");

    this.nextAttemptAt = leaseUntil;
  }

  /** 시도를 다 썼는지 (NT-03). 다 쓴 건은 DLQ 로 옮긴다. */
  public boolean hasExhausted(int maxAttempts) {
    return attempts >= maxAttempts;
  }

  private void requirePending() {
    if (status != OutboxStatus.PENDING) {
      throw new IllegalStateException("이미 보낸 알림이다. id=" + id);
    }
  }
}
