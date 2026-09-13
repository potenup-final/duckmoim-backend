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
 * 이 사람이 끈 알림 종류 (NT-11).
 *
 * <p><b>끈 것만 적는다.</b> 행이 있으면 끈 것이고 없으면 받는 것이다 — 기본값이 「셋 다 켜짐」이라 켜진 상태를 행으로 남기면 가입한 사람마다 쓸모없는 행이 셋씩
 * 생긴다.
 *
 * <p><b>애그리게이트가 아니다.</b> 자기 불변식도 라이프사이클도 없다. 만들어지거나 지워질 뿐 고쳐지지 않아 {@code AuditLog} · {@code
 * NotificationOutbox} 와 같은 자리다 (도메인-모델링.md 「3.1 경계와 트랜잭션 범위」 의 표에 없다).
 *
 * <p><b>{@code common} 에 있는 이유는 읽는 쪽이다.</b> 이 값을 보는 것은 아웃박스 발행 직전이고 (NT-01), 그 발행을 부르는 쪽이
 * Companion(댓글)과 Chat(메시지) 둘이다. {@code notification} 안에 두면 그 둘이 Notification 을 참조하게 되어 의존이 거꾸로 흐른다
 * — {@link NotificationOutbox} 가 여기 있는 것과 같은 근거다.
 *
 * <p>회원번호로만 참조한다 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」). FK 는 걸지 않는다.
 */
@Entity
@Table(name = "notification_mute")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationMute extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 설정을 가진 사람의 회원번호. */
  @Column(name = "user_id", nullable = false, updatable = false)
  private Long userId;

  /**
   * 끈 종류.
   *
   * <p><b>두 값 다 만든 뒤에 바뀌지 않는다.</b> 켜는 것은 이 행을 고치는 것이 아니라 지우는 것이다 — 「행이 없는 것이 곧 받는다」가 그 뜻이고, 고치는 길을
   * 열어 두면 같은 상태를 두 가지 모양으로 표현하게 된다.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, updatable = false, length = 20)
  private NotificationKind kind;

  private NotificationMute(Long userId, NotificationKind kind) {
    this.userId = userId;
    this.kind = kind;
  }

  /** 이 사람이 이 종류를 껐다고 적는다. */
  public static NotificationMute of(Long userId, NotificationKind kind) {
    Objects.requireNonNull(userId, "수신 설정은 주인을 가진다.");
    Objects.requireNonNull(kind, "수신 설정은 끈 종류를 가진다.");

    return new NotificationMute(userId, kind);
  }
}
