package com.duckmoim.safety.domain;

import com.duckmoim.common.domain.BaseEntity;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.safety.exception.ReportErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 유저 · 모집글 · 댓글에 대한 문제 제기 (SF-01 · SF-02 · SF-07 · CM-14).
 *
 * <p><b>대상 종류만 다른 하나의 애그리게이트다.</b> API-설계.md 「2-6. 신고 (Safety)」가 엔드포인트를 하나로 정했고, 세 담당자가 각자 만들면 같은
 * 애그리게이트를 셋이 건드린다.
 *
 * <p>대상을 {@code (targetType, targetId)} 두 컬럼으로 가리킨다. 종류가 셋이라 한 컬럼으로는 안 되고, 그래서 FK 도 걸 수 없다 — 컨텍스트
 * 맵이 Safety → Identity 를 [ID 참조] 로 정한 것과 같은 방향이다.
 *
 * <p><b>처리 이력을 이 애그리게이트가 진다</b> (도메인-모델링.md 「1. 유비쿼터스 언어」 각주). 감사 로그 다섯에 신고 처리가 없는 이유가 이것이다 — 같은
 * 사실을 두 곳에 두면 갈라진다. 그래서 {@code result} · {@code memo} · {@code handledBy} · {@code handledAt} 가 여기
 * 있다.
 */
@Entity
@Table(name = "report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "reporter_id", nullable = false)
  private Long reporterId;

  @Enumerated(EnumType.STRING)
  @Column(name = "target_type", nullable = false, length = 20)
  private ReportTargetType targetType;

  @Column(name = "target_id", nullable = false)
  private Long targetId;

  @Enumerated(EnumType.STRING)
  @Column(name = "reason", nullable = false, length = 30)
  private ReportReason reason;

  @Column(name = "detail", length = 500)
  private String detail;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private ReportStatus status;

  // 무엇으로 종결했는가 (AD-03). 처리 전에는 null 이다.
  @Enumerated(EnumType.STRING)
  @Column(name = "result", length = 30)
  private ReportResult result;

  // 관리자가 남기는 판단 맥락. 선택이고 길이는 detail 과 같게 맞췄다.
  @Column(name = "memo", length = 500)
  private String memo;

  // 애그리게이트 밖은 ID 로만 참조한다 (도메인 3.2).
  @Column(name = "handled_by")
  private Long handledBy;

  @Column(name = "handled_at")
  private LocalDateTime handledAt;

  private Report(
      Long reporterId,
      ReportTargetType targetType,
      Long targetId,
      ReportReason reason,
      String detail) {

    this.reporterId = reporterId;
    this.targetType = targetType;
    this.targetId = targetId;
    this.reason = reason;
    this.detail = detail;
    this.status = ReportStatus.PENDING;
  }

  /**
   * 신고를 접수한다.
   *
   * <p><b>대상과 사유의 조합을 여기서 판정한다.</b> API 설계가 <i>"검증은 값 자체뿐 아니라 대상과의 조합까지 본다. USER 전용 사유를 COMMENT 로
   * 보내면 REPORT_REASON_INVALID 400 이다"</i> 라고 정했다. 값 자체가 enum 에 없는 것은 역직렬화가 먼저 걸러 {@code
   * INVALID_INPUT} 400 이 된다.
   *
   * <p>중복은 여기서 못 본다 — 이미 신고했는지는 저장소를 봐야 알고, 동시 요청은 유니크 제약이 막는다 (V34).
   */
  public static Report of(
      Long reporterId,
      ReportTargetType targetType,
      Long targetId,
      ReportReason reason,
      String detail) {

    if (!reason.supports(targetType)) {
      throw new BusinessException(ReportErrorCode.REPORT_REASON_INVALID);
    }

    return new Report(reporterId, targetType, targetId, reason, detail);
  }

  /**
   * 관리자가 신고를 처리한다 (AD-03).
   *
   * <p><b>되돌리는 전이가 없다</b> (도메인-모델링.md 「6. 라이프사이클」). {@code RESOLVED} 가 종착이고, 이미 처리된 신고를 다시 처리하면 409
   * 다. {@code PROCESSING} 에서 {@code PENDING} 으로 놓아주는 것도 전이가 아니다 — 잡은 사람이 손을 떼는 경로는 문서에 없다.
   *
   * <p><b>{@code PENDING → RESOLVED} 직행은 허용한다.</b> 같은 문서가 <i>"볼 것도 없이 끝나는 건까지 두 번 누르게 할 이유가 없다"</i>
   * 고 정했다.
   *
   * <p><b>{@code PROCESSING} 에는 결과를 남기지 않는다.</b> 그것은 「내가 잡았다」는 표시이지 종결이 아니다 — 관리자가 넷이고 창구가 하나뿐이라 같은
   * 건을 둘이 동시에 붙잡는 것을 막는 것이 그 상태의 존재 이유다.
   *
   * <p><b>요청자를 인가로 쓰지 않는다.</b> 관리자 판정은 관문 한 곳에 있다 (API-설계.md 「2-7」의 조건 2). 여기서 받는 것은 <b>이력</b>이다 —
   * 누가 처리했는지가 이 애그리게이트에 남아야 한다 (도메인 1.1 각주).
   *
   * @param result 종결 사유. {@code RESOLVED} 로 갈 때만 쓰인다
   * @param memo 관리자가 남기는 판단 맥락. 없어도 된다
   */
  public void handle(
      ReportStatus next, ReportResult result, String memo, Long adminUserId, LocalDateTime now) {

    if (status == ReportStatus.RESOLVED) {
      throw new BusinessException(ReportErrorCode.REPORT_ALREADY_HANDLED);
    }
    if (!status.canMoveTo(next)) {
      throw new BusinessException(ReportErrorCode.REPORT_TRANSITION_NOT_ALLOWED);
    }

    this.status = next;
    this.handledBy = adminUserId;
    this.handledAt = now;

    if (next == ReportStatus.RESOLVED) {
      this.result = result;
      this.memo = memo;
    }
  }
}
