package com.duckmoim.safety.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 제재 목록 조회 조건 (AD-10). */
class SanctionListQueryTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 14, 0, 0);

  @DisplayName("범위를 벗어난 size 는 거절하지 않고 자른다.")
  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource({"0, 20", "-1, 20", "1, 1", "50, 50", "51, 50", "1000, 50"})
  void clampsSize(int given, int expected) {
    assertThat(new SanctionListQuery(null, NOW, null, given).size()).isEqualTo(expected);
  }

  /** 「활성 제재를 훑는다」가 AD-10 의 기본이라 필터 없는 쪽이 기본이다. */
  @DisplayName("종류를 주지 않으면 거르지 않는다.")
  @Test
  void hasNoKind() {
    assertThat(new SanctionListQuery(null, NOW, null, 20).hasKind()).isFalse();
  }

  @DisplayName("종류를 주면 그것으로 거른다.")
  @Test
  void hasKind() {
    SanctionListQuery query = new SanctionListQuery(SanctionKind.SUSPENDED, NOW, null, 20);

    assertThat(query.hasKind()).isTrue();
    assertThat(query.kind()).isEqualTo(SanctionKind.SUSPENDED);
  }

  @DisplayName("커서가 없으면 첫 페이지다.")
  @Test
  void hasNoCursor() {
    assertThat(new SanctionListQuery(null, NOW, null, 20).hasCursor()).isFalse();
  }
}
