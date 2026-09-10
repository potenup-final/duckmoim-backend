package com.duckmoim.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
   * 발송 상태. <b>이 컬럼만 만든 뒤에 바뀐다.</b>
   *
   * <p>나머지 컬럼이 {@code updatable = false} 인 것은 「보낼 것」이 발행 시점에 정해지기 때문이다. 워커가 고칠 수 있는 것은 「보냈는지」 하나다.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private OutboxStatus status;

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
}
