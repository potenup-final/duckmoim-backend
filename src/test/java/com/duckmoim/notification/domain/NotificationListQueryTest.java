package com.duckmoim.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 목록 크기는 거절하지 않고 자른다 (API-설계.md 「검증 상한」 — 1~50 으로 조용히 맞춘다). */
class NotificationListQueryTest {

  @DisplayName("범위를 벗어난 크기는 거절하지 않고 자른다.")
  @ParameterizedTest(name = "요청 {0} → {1}")
  @CsvSource({"0, 20", "-1, 20", "1, 1", "20, 20", "50, 50", "51, 50", "1000, 50"})
  void size_isOutOfRange(int requested, int expected) {
    NotificationListQuery query = new NotificationListQuery(7L, null, requested);

    assertThat(query.size()).isEqualTo(expected);
  }

  @DisplayName("커서가 없으면 첫 페이지다.")
  @Test
  void hasCursor_isFirstPage() {
    assertThat(new NotificationListQuery(7L, null, 20).hasCursor()).isFalse();
    assertThat(
            new NotificationListQuery(
                    7L, new NotificationCursor(LocalDateTime.of(2026, 9, 14, 0, 0), 42L), 20)
                .hasCursor())
        .isTrue();
  }
}
