package com.duckmoim.admin.domain;

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
 * 관리자의 행위 기록 (AD-05).
 *
 * <p><b>애그리게이트가 아니다.</b> 자기 불변식도 라이프사이클도 없는 append-only 기술 기록이라 도메인-모델링.md 「3. 애그리게이트」 표에 없다. 제재
 * 실행과 비밀 댓글 열람의 부수 효과로만 쌓인다.
 *
 * <p><b>수정자가 없다.</b> I-13 「감사 로그는 수정·삭제되지 않는다」의 검증 위치가 「append-only 경로만 제공」이다. 만드는 문이 {@link #of}
 * 하나뿐이고, 만든 뒤에 바꾸는 문이 없다.
 *
 * <p><b>{@code BaseEntity} 를 상속하지 않는다.</b> {@code updated_at} 은 「수정되지 않는다」와 정면으로 어긋나는 컬럼이다. 행위 시각이
 * 곧 생성 시각이라 둘을 나눌 이유도 없어 {@link #at} 하나를 둔다 — 이름은 화면-계약.md 의 응답 필드를 따랐다.
 *
 * <p>행위자와 대상을 ID 로만 참조한다 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」). 대상 종류가 둘이라 FK 도 걸 수 없다 — {@code Report}
 * 와 같은 자리다.
 */
@Entity
@Table(name = "audit_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /**
   * 행위자의 회원번호다. <b>카카오 회원번호가 아니다.</b>
   *
   * <p>인가는 {@code AdminAccount} 가 카카오 회원번호로 판정하지만 (D-5), 기록에는 회원번호를 남긴다 — 카카오 회원번호는 어느 응답에도 나가지 않고,
   * 화면에 내릴 닉네임을 얻으려면 어차피 회원번호로 조인해야 한다.
   */
  @Column(name = "actor_user_id", nullable = false)
  private Long actorUserId;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 20)
  private AuditKind kind;

  @Enumerated(EnumType.STRING)
  @Column(name = "target_type", nullable = false, length = 20)
  private AuditTargetType targetType;

  @Column(name = "target_id", nullable = false)
  private Long targetId;

  /** 무엇을 왜 했는지의 한 줄. 선택 입력이라 없을 수 있다. */
  @Column(name = "detail", length = 500)
  private String detail;

  @Column(name = "at", nullable = false, updatable = false)
  private LocalDateTime at;

  private AuditLog(
      Long actorUserId, AuditKind kind, Long targetId, String detail, LocalDateTime at) {

    this.actorUserId = actorUserId;
    this.kind = kind;
    this.targetType = kind.targetType();
    this.targetId = targetId;
    this.detail = detail;
    this.at = at;
  }

  /**
   * 행위를 기록한다.
   *
   * <p><b>{@code targetType} 을 받지 않는다.</b> {@link AuditKind} 가 이미 갖고 있다. 따로 받으면 {@code BLIND} 인데
   * {@code USER} 를 가리키는 줄이 만들어질 수 있고, 고칠 수 없어 영영 남는다 (I-13).
   *
   * <p><b>{@code at} 을 인자로 받는다.</b> {@code LocalDateTime.now()} 를 여기서 부르면 도메인이 시계를 쥐게 되어 시각이 걸린 검사를
   * 짤 수 없다. 시계는 service 가 주입받는다 ({@code ClockConfig}).
   */
  public static AuditLog of(
      Long actorUserId, AuditKind kind, Long targetId, String detail, LocalDateTime at) {

    Objects.requireNonNull(actorUserId, "감사 로그는 행위자를 가진다.");
    Objects.requireNonNull(kind, "감사 로그는 행위 종류를 가진다.");
    Objects.requireNonNull(targetId, "감사 로그는 대상을 가진다.");
    Objects.requireNonNull(at, "감사 로그는 행위 시각을 가진다.");

    return new AuditLog(actorUserId, kind, targetId, detail, at);
  }
}
