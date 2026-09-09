package com.duckmoim.safety.domain;

/**
 * 백오피스 신고 목록의 조회 조건 (AD-02).
 *
 * <p><b>거르는 조건이 처리 상태 하나다.</b> 명세서 AD-02 가 「처리 상태 필터」라고만 적었고 화면 계약도 <i>"{@code status} 로 거른다"</i>
 * 외에 다른 필터를 두지 않았다. 대상 종류나 사유로도 거르고 싶어지는 자리인데, 지금 지어내면 프론트가 쓰지 않는 파라미터가 남는다.
 *
 * @param status 안 주면 전량이다. 「접수 건 전량 조회」가 AD-02 의 검증 기준이라 필터 없는 쪽이 기본이다
 * @param cursor 첫 페이지면 null 이다
 * @param size 클라이언트가 준 값. 범위를 벗어나면 잘린다
 */
public record ReportListQuery(ReportStatus status, ReportCursor cursor, int size) {

  /** API 컨벤션이 정한 기본값과 상한이다. */
  public static final int DEFAULT_SIZE = 20;

  public static final int MAX_SIZE = 50;

  public ReportListQuery {
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

  public boolean hasStatus() {
    return status != null;
  }
}
