package com.duckmoim.notification.domain;

import com.duckmoim.common.domain.BaseEntity;
import com.duckmoim.common.domain.NotificationKind;
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
 * 알림함의 알림 한 건 (NT-02).
 *
 * <p><b>아웃박스와 다른 표다.</b> 아웃박스는 「보낼 것」을 적어두는 기술 기록이고 이것은 사용자가 보는 알림이다. 그래서 아웃박스는 어느 컨텍스트에도 없지만
 * (도메인-모델링.md 「3.1 경계와 트랜잭션 범위」 각주) 이쪽은 애그리게이트다 — 같은 문서 표에 「수신자, 종류, 대상 참조, 읽음」 으로 경계가 적혀 있다.
 *
 * <p><b>대상 참조를 아웃박스에서 옮겨 온다.</b> 워커는 Companion 에 물어볼 수 없다 — 도메인-모델링.md 「2. 바운디드 컨텍스트」 의 화살표가
 * Notification 에서 Identity 로 하나만 나간다. 아웃박스 행이 실어 온 값이 알림에 필요한 전부다.
 *
 * <p><b>{@link #outboxId} 가 멱등성 키다.</b> 알림을 만든 뒤 아웃박스 상태를 바꾸기 전에 워커가 죽으면 다음 시도가 같은 행을 다시 집는다. 유니크
 * 제약이 그때 둘째 알림을 막고, 인스턴스가 둘일 때 두 워커가 같은 행을 집는 경우도 함께 막는다 — 선점 자체는 NT-04 가 넣는다.
 */
@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

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

  /**
   * 읽은 시각. NULL 이면 안 읽은 것이다.
   *
   * <p><b>이 값을 바꾸는 전이가 여기 없다.</b> NT-09 의 읽음 처리는 {@code NotificationRepository} 의 조건부 UPDATE 가 쥔다 —
   * 이유는 그쪽에 적혀 있다. 만들어질 때는 늘 NULL 이다.
   */
  @Column(name = "read_at")
  private LocalDateTime readAt;

  private Notification(
      Long outboxId, Long recipientId, NotificationKind kind, Long postId, Long commentId) {

    this.outboxId = outboxId;
    this.recipientId = recipientId;
    this.kind = kind;
    this.postId = postId;
    this.commentId = commentId;
  }

  /**
   * 아웃박스 행 하나를 알림으로 옮긴다.
   *
   * <p><b>읽음 여부를 인자로 받지 않는다.</b> 만들어지는 알림은 늘 안 읽은 것이고, 읽음으로 바꾸는 문은 NT-09 가 낸다.
   */
  public static Notification of(
      Long outboxId, Long recipientId, NotificationKind kind, Long postId, Long commentId) {

    Objects.requireNonNull(outboxId, "알림은 어느 발행에서 나왔는지를 가진다.");
    Objects.requireNonNull(recipientId, "알림은 수신자를 가진다.");
    Objects.requireNonNull(kind, "알림은 종류를 가진다.");
    Objects.requireNonNull(postId, "알림은 모집글을 가진다.");
    Objects.requireNonNull(commentId, "알림은 댓글을 가진다.");

    return new Notification(outboxId, recipientId, kind, postId, commentId);
  }

  /** 안 읽은 알림인지. 목록과 배지가 이것으로 갈린다 (NT-08 · NT-10). */
  public boolean isUnread() {
    return readAt == null;
  }
}
