package com.duckmoim.companion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 목록 크기는 거절하지 않고 자른다 (API 컨벤션 — 기본 20 · 최대 50). */
class PostListQueryTest {

  private static final LocalDateTime MEET_AT = LocalDateTime.of(2026, 9, 14, 0, 0);

  @DisplayName("size 가 범위를 벗어나면 자른다.")
  @ParameterizedTest(name = "요청 {0} → {1}")
  @CsvSource({"0, 20", "-1, 20", "1, 1", "20, 20", "50, 50", "51, 50", "1000, 50"})
  void size_isOutOfRange(int requested, int expected) {
    PostListQuery query = new PostListQuery(PostStatus.OPEN, null, requested);

    assertThat(query.size()).isEqualTo(expected);
  }

  @DisplayName("커서가 없으면 첫 페이지다.")
  @Test
  void hasCursor_isFirstPage() {
    assertThat(new PostListQuery(null, null, 20).hasCursor()).isFalse();
    assertThat(new PostListQuery(null, new PostCursor(MEET_AT, 1L), 20).hasCursor()).isTrue();
  }

  /** 「모집중 / 전체」 둘뿐이고 전체가 필터 없음이다 (PO-08 · API 설계 2-4). */
  @DisplayName("상태가 없으면 거르지 않는다.")
  @Test
  void hasStatus_isAll() {
    assertThat(new PostListQuery(null, null, 20).hasStatus()).isFalse();
    assertThat(new PostListQuery(PostStatus.OPEN, null, 20).hasStatus()).isTrue();
  }
}
