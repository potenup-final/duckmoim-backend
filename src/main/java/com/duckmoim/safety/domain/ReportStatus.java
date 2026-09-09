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
  RESOLVED;

  /**
   * 이 상태에서 {@code next} 로 갈 수 있는가 (AD-03).
   *
   * <p><b>판정이 위 그림과 같은 파일에 있다.</b> 전이표를 서비스에 두면 그림과 코드가 따로 낡는다.
   *
   * <p>허용하는 것은 셋뿐이다 — {@code PENDING → PROCESSING} · {@code PENDING → RESOLVED} · {@code PROCESSING
   * → RESOLVED}. 제자리 전이({@code PENDING → PENDING})도 막는다. 아무것도 바꾸지 않는 요청을 성공으로 답하면 처리 시각과 처리자만
   * 덮어써진다.
   */
  public boolean canMoveTo(ReportStatus next) {
    return switch (this) {
      case PENDING -> next == PROCESSING || next == RESOLVED;
      case PROCESSING -> next == RESOLVED;
      case RESOLVED -> false;
    };
  }
}
