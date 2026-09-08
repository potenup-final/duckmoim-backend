package com.duckmoim.admin.domain;

/**
 * 감사 로그 목록의 조회 조건 (AD-05).
 *
 * <p><b>거르는 조건이 없다.</b> API-설계.md 2-7 이 이 경로를 「조회만」으로 적었고 화면-계약.md 에도 필터가 없다. 관리자만 닿는 목록이라 좁힐 이유가
 * 아직 없다 — 필요해지면 그때 계약에 넣는다. 지금 지어내면 프론트가 쓰지 않는 파라미터가 남는다.
 *
 * @param cursor 첫 페이지면 null 이다
 * @param size 클라이언트가 준 값. 범위를 벗어나면 잘린다
 */
public record AuditLogListQuery(AuditLogCursor cursor, int size) {

  /** API 컨벤션이 정한 기본값과 상한이다. */
  public static final int DEFAULT_SIZE = 20;

  public static final int MAX_SIZE = 50;

  public AuditLogListQuery {
    size = clampSize(size);
  }

  /**
   * 범위를 벗어난 {@code size} 는 거절하지 않고 자른다.
   *
   * <p>목록 크기는 클라이언트의 편의값이지 계약 위반이 아니다. 다른 목록과 같은 판단이다.
   */
  private static int clampSize(int size) {
    if (size < 1) {
      return DEFAULT_SIZE;
    }
    return Math.min(size, MAX_SIZE);
  }

  public boolean hasCursor() {
    return cursor != null;
  }
}
