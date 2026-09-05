package com.duckmoim.catalog.domain;

import java.time.LocalDate;

/**
 * 행사 목록 조회 조건 (EV-05).
 *
 * <p>필터는 전부 선택이고 {@code null} 은 "거르지 않는다" 는 뜻이다. 조합에 제약이 없다 — 검증 기준이 "필터 조합별 결과 정확" 이라 넷을 동시에 걸어도
 * 성립해야 한다.
 *
 * <p>정렬은 {@code (endsOn, id)} 오름차순 고정, 곧 마감 임박 순이다 (EV-05). 파라미터로 받지 않는 이유는 커서가 정렬 키를 담고 있어서다 — 정렬이
 * 바뀌면 이미 발급한 커서가 무의미해진다.
 *
 * <p><b>끝난 행사는 여기 없다.</b> 조회 시점({@code today})은 이 조건에 담지 않고 저장소 인자로 받는다. 사용자가 끌 수 있는 필터가 아니라 목록 자체의
 * 성질이라서다 (화면-계약/행사.md 9장).
 */
public record EventQuery(
    EventKind kind,
    Long regionId,
    LocalDate dateFrom,
    LocalDate dateTo,
    String keyword,
    EventCursor cursor,
    int size) {

  public static final int DEFAULT_SIZE = 20;
  public static final int MAX_SIZE = 50;
  private static final int MIN_SIZE = 1;

  public EventQuery {
    size = clampSize(size);
    keyword = normalizeKeyword(keyword);
  }

  /**
   * 범위를 벗어난 {@code size} 는 거절하지 않고 자른다.
   *
   * <p>API 컨벤션은 "기본 20, 최대 50" 만 적고 초과했을 때를 정하지 않았다. 목록 조회는 읽기 전용이고 무한 스크롤이 부르는 자리라, 요청을 깨뜨리는 것보다
   * 상한을 지키며 응답하는 쪽이 낫다고 보았다.
   */
  private static int clampSize(int size) {
    if (size < MIN_SIZE) {
      return DEFAULT_SIZE;
    }
    return Math.min(size, MAX_SIZE);
  }

  /** 공백만 있는 키워드는 필터가 아니다. 그대로 두면 LIKE '%%' 가 되어 전체를 훑는다. */
  private static String normalizeKeyword(String keyword) {
    if (keyword == null || keyword.isBlank()) {
      return null;
    }
    return keyword.strip();
  }

  public boolean hasCursor() {
    return cursor != null;
  }
}
