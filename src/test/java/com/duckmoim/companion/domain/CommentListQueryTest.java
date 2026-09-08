package com.duckmoim.companion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 목록 크기는 거절하지 않고 자른다 (API 컨벤션 — 기본 20 · 최대 50). */
class CommentListQueryTest {

  @DisplayName("범위를 벗어난 크기는 거절하지 않고 자른다.")
  @ParameterizedTest(name = "요청 {0} → {1}")
  @CsvSource({"0, 20", "-1, 20", "1, 1", "20, 20", "50, 50", "51, 50", "1000, 50"})
  void size_isOutOfRange(int requested, int expected) {
    CommentListQuery query = new CommentListQuery(1L, null, requested);

    assertThat(query.size()).isEqualTo(expected);
  }

  @DisplayName("커서가 없으면 첫 페이지다.")
  @Test
  void hasCursor_isFirstPage() {
    assertThat(new CommentListQuery(1L, null, 20).hasCursor()).isFalse();
    assertThat(
            new CommentListQuery(1L, new CommentCursor(LocalDateTime.of(2026, 9, 14, 9, 0), 1L), 20)
                .hasCursor())
        .isTrue();
  }
}
