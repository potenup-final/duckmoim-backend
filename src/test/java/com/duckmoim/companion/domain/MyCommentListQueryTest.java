package com.duckmoim.companion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 목록 크기는 거절하지 않고 자른다 (API 컨벤션 — 기본 20 · 최대 50). */
class MyCommentListQueryTest {

  @DisplayName("범위를 벗어난 크기는 거절하지 않고 자른다.")
  @ParameterizedTest(name = "요청 {0} → {1}")
  @CsvSource({"0, 20", "-1, 20", "1, 1", "20, 20", "50, 50", "51, 50", "1000, 50"})
  void size_isOutOfRange(int requested, int expected) {
    MyCommentListQuery query = new MyCommentListQuery(7L, null, requested);

    assertThat(query.size()).isEqualTo(expected);
  }

  @DisplayName("댓글 목록과 같은 크기 한도를 쓴다.")
  @Test
  void size_sharesLimitWithCommentList() {
    assertThat(new MyCommentListQuery(7L, null, 0).size())
        .isEqualTo(new CommentListQuery(1L, null, 0).size());
    assertThat(new MyCommentListQuery(7L, null, 1000).size())
        .isEqualTo(new CommentListQuery(1L, null, 1000).size());
  }

  @DisplayName("커서가 없으면 첫 페이지다.")
  @Test
  void hasCursor_isFirstPage() {
    assertThat(new MyCommentListQuery(7L, null, 20).hasCursor()).isFalse();
    assertThat(
            new MyCommentListQuery(
                    7L, new MyCommentCursor(LocalDateTime.of(2026, 8, 30, 9, 40), 31L), 20)
                .hasCursor())
        .isTrue();
  }
}
