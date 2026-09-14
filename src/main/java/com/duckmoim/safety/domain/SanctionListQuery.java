package com.duckmoim.safety.domain;

import java.time.LocalDateTime;

/**
 * 백오피스 제재 목록의 조회 조건 (AD-10).
 *
 * <p><b>거르는 조건이 종류 하나다.</b> 명세서 AD-10 이 「종류 필터(경고 / 나이 확인 / 기간 정지 / 영구 정지)」라고만 적었다. 회원 닉네임이나 제재 시점
 * 구간으로도 거르고 싶어지는 자리인데, 지금 지어내면 화면이 쓰지 않는 파라미터가 남는다.
 *
 * <p><b>{@code now} 가 조건에 들어간다.</b> 다른 목록과 다른 자리다 — 활성 제재만 담기므로 「지금」을 넘겨야 만료된 것이 빠진다 (검증 기준 ②). 시계를
 * service 가 쥐고 값으로 넘기는 것은 테스트가 시각을 고정할 수 있어야 해서다.
 *
 * @param kind 안 주면 전량이다. 「활성 제재를 훑는다」가 AD-10 의 기본이라 필터 없는 쪽이 기본이다
 * @param now 이 시각에 유효한 제재만 담긴다. 저장과 같은 UTC 다
 * @param cursor 첫 페이지면 null 이다
 * @param size 클라이언트가 준 값. 범위를 벗어나면 잘린다
 */
public record SanctionListQuery(
    SanctionKind kind, LocalDateTime now, SanctionCursor cursor, int size) {

  /** API 컨벤션이 정한 기본값과 상한이다. {@code ReportListQuery} 와 같은 값을 쓴다. */
  public static final int DEFAULT_SIZE = 20;

  public static final int MAX_SIZE = 50;

  public SanctionListQuery {
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

  public boolean hasKind() {
    return kind != null;
  }
}
