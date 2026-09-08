package com.duckmoim.safety.domain;

/**
 * 신고 처리 상태 (도메인-모델링.md 「6. 라이프사이클」).
 *
 * <pre>
 * PENDING ──관리자가 잡음──► PROCESSING ──처리──► RESOLVED
 *    └──────────────처리───────────────────────────┘
 * </pre>
 *
 * <p><b>접수는 PENDING 으로만 들어온다.</b> 나머지 전이는 백오피스(AD-03)가 만들고, {@code RESOLVED} 가 종착이라 되돌리는 전이가 없다.
 *
 * <p>{@code PROCESSING} 이 있는 이유는 관리자가 넷이고 백오피스 창구가 하나뿐이라, 없으면 같은 신고를 둘이 동시에 붙잡고 각자 조치하기 때문이다.
 */
public enum ReportStatus {
  PENDING,
  PROCESSING,
  RESOLVED
}
