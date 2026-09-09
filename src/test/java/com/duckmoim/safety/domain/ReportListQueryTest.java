package com.duckmoim.safety.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 신고 목록 조회 조건 (AD-02). */
class ReportListQueryTest {

  @DisplayName("범위를 벗어난 size 는 거절하지 않고 자른다.")
  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource({"0, 20", "-1, 20", "1, 1", "50, 50", "51, 50", "1000, 50"})
  void clampsSize(int given, int expected) {
    assertThat(new ReportListQuery(null, null, given).size()).isEqualTo(expected);
  }

  /** 「접수 건 전량 조회」가 AD-02 의 검증 기준이라 필터 없는 쪽이 기본이다. */
  @DisplayName("상태를 주지 않으면 거르지 않는다.")
  @Test
  void hasNoStatus() {
    assertThat(new ReportListQuery(null, null, 20).hasStatus()).isFalse();
  }

  @DisplayName("상태를 주면 그것으로 거른다.")
  @Test
  void hasStatus() {
    ReportListQuery query = new ReportListQuery(ReportStatus.PENDING, null, 20);

    assertThat(query.hasStatus()).isTrue();
    assertThat(query.status()).isEqualTo(ReportStatus.PENDING);
  }

  @DisplayName("커서가 없으면 첫 페이지다.")
  @Test
  void hasNoCursor() {
    assertThat(new ReportListQuery(null, null, 20).hasCursor()).isFalse();
  }
}
