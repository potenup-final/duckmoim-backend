package com.duckmoim.notification.domain;

import com.duckmoim.common.domain.BaseEntity;
import com.duckmoim.common.domain.NotificationKind;
import com.duckmoim.common.domain.NotificationOutbox;
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
 * 시도를 다 쓰고 못 보낸 알림 (NT-03).
 *
 * <p><b>아웃박스와 표를 가른 이유.</b> 명세 문면이 「별도 표로 옮기고」다. 아웃박스는 워커가 10초마다 훑는 표라 죽은 건이 그 안에 쌓이면 훑는 양이 계속 늘고,
 * 조회 조건에 「죽지 않은 것」이 하나 더 붙는다.
 *
 * <p><b>{@code common} 이 아니라 여기 있다.</b> 아웃박스가 {@code common} 인 것은 행을 넣는 쪽이 Companion 과 Chat 둘이기
 * 때문인데 (도메인-모델링.md 「2. 바운디드 컨텍스트」), 이 표는 넣는 쪽도 읽는 쪽도 알림뿐이다.
 *
 * <p><b>아웃박스가 들고 있던 값을 그대로 옮긴다.</b> 원본 행은 옮기면서 지우므로, 여기 없는 값은 영영 알 수 없다.
 */
@Entity
@Table(name = "notification_outbox_dlq")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationOutboxDlq extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 옮겨 오기 전 아웃박스에서의 번호. 워커 로그에 남은 번호로 이 행을 찾는다. */
  @Column(name = "outbox_id", nullable = false, updatable = false)
  private Long outboxId;

  @Column(name = "recipient_id", nullable = false, updatable = false)
  private Long recipientId;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, updatable = false, length = 20)
  private NotificationKind kind;

  @Column(name = "post_id", nullable = false, updatable = false)
  private Long postId;

  @Column(name = "comment_id", nullable = false, updatable = false)
  private Long commentId;

  /** 몇 번 시도하고 포기했는지. 상한이 설정값이라 나중에 바뀌어도 그때의 값이 남는다. */
  @Column(name = "attempts", nullable = false, updatable = false)
  private int attempts;

  /** 포기한 시각. 아웃박스의 생성 시각은 발행 시각이라 다른 값이다. */
  @Column(name = "failed_at", nullable = false, updatable = false)
  private LocalDateTime failedAt;

  private NotificationOutboxDlq(NotificationOutbox outbox, LocalDateTime failedAt) {
    this.outboxId = outbox.getId();
    this.recipientId = outbox.getRecipientId();
    this.kind = outbox.getKind();
    this.postId = outbox.getPostId();
    this.commentId = outbox.getCommentId();
    this.attempts = outbox.getAttempts();
    this.failedAt = failedAt;
  }

  /**
   * 못 보낸 아웃박스 행을 옮겨 담는다.
   *
   * <p><b>실패 사유를 받지 않는다.</b> 예외는 워커 로그에 스택 트레이스와 함께 남는다. 사유 문자열을 표에 두면 무엇을 담아도 되는 칸이 되어 개인정보가 흘러들
   * 자리가 생긴다.
   */
  public static NotificationOutboxDlq of(NotificationOutbox outbox, LocalDateTime failedAt) {
    Objects.requireNonNull(outbox, "옮길 아웃박스 행이 필요하다.");
    Objects.requireNonNull(failedAt, "포기한 시각이 필요하다.");

    return new NotificationOutboxDlq(outbox, failedAt);
  }
}
