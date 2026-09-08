package com.duckmoim.safety.domain;

import java.util.Set;

/**
 * 신고 사유 (API-설계.md 「2-6. 신고 (Safety)」의 대상×사유 조합표).
 *
 * <p><b>어느 대상에 붙는지를 값이 스스로 안다.</b> 조합표가 값 집합이자 검증 규칙이라, 표를 코드 밖에 두면 검증이 표와 갈라진다. 공통 셋은 세 대상 모두에,
 * 대상별은 그 대상에만 붙는다.
 *
 * <p><b>사유 목록을 서버가 내려주지 않는다</b> (결정 D-6). 목록은 클라이언트가 갖고 서버는 검증만 한다 — <i>"화면에 무엇이 떴든 API 는 직접 호출될 수
 * 있다."</i>
 *
 * <p><b>{@code AGE_SUSPICION} 을 {@code UNDERAGE} 로 쓰지 않는다.</b> 화면 문구가 「만 14세 미만으로 보입니다」가 아니라 「나이를
 * 속인 것 같아요」인 이유 — <i>신고자에게 남의 나이를 판정시키지 않는다</i> — 가 <b>코드명에도 남아야 한다</b>고 문서가 못박았다. 판정을 시키면 어려 보인다는
 * 인상만으로 신고가 쌓이고 그걸로는 아무것도 못 한다.
 *
 * <p><b>사칭 사유를 두지 않는다</b> (2026-09-05). 1차에 신원 확인 수단이 아예 없어 조치할 수 없는 신고만 쌓인다. 주최자 사칭은 모집글 내용이라
 * {@code POST} 의 {@code FALSE_INFO} 로 받는다.
 */
public enum ReportReason {
  /** 광고 · 홍보. */
  ADVERTISEMENT(ReportTargetType.values()),
  /** 부적절한 내용. */
  INAPPROPRIATE(ReportTargetType.values()),
  /** 욕설 · 비방. */
  ABUSE(ReportTargetType.values()),
  /** 약속을 지키지 않음. */
  NO_SHOW(ReportTargetType.USER),
  /** 나이를 속인 것 같아요. */
  AGE_SUSPICION(ReportTargetType.USER),
  /** 허위 정보. 모집글과 댓글 둘 다에 붙는다. */
  FALSE_INFO(ReportTargetType.POST, ReportTargetType.COMMENT),
  /** 동행과 무관한 글. */
  OFF_TOPIC(ReportTargetType.POST);

  private final Set<ReportTargetType> targetTypes;

  ReportReason(ReportTargetType... targetTypes) {
    this.targetTypes = Set.of(targetTypes);
  }

  /** 이 사유를 그 대상에 쓸 수 있는지 본다. 검증은 값 자체뿐 아니라 <b>대상과의 조합까지</b> 본다. */
  public boolean supports(ReportTargetType targetType) {
    return targetTypes.contains(targetType);
  }
}
