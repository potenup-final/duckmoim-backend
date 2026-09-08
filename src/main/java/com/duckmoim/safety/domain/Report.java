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
}
