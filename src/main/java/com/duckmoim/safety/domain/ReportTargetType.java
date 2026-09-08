package com.duckmoim.safety.domain;

/**
 * 신고 대상 (도메인-모델링.md 「1.1 열거값」. 값은 API-설계.md 「2-6. 신고 (Safety)」).
 *
 * <p>대상이 셋인데 계약은 하나다 — <i>"화면이 셋이어도 계약은 하나다 (SF-07)"</i>. 시트도 하나를 쓰고 대상만 바뀐다.
 */
public enum ReportTargetType {
  USER,
  POST,
  COMMENT
}
