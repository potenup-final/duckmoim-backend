package com.duckmoim.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EventQueryTest {

  private static EventQuery withSize(int size) {
    return new EventQuery(null, null, null, null, null, null, size);
  }

  @DisplayName("요청한 size 가 상한 안이면 그대로 쓴다.")
  @Test
  void size() {
    assertThat(withSize(30).size()).isEqualTo(30);
  }

  @DisplayName("size 가 범위를 벗어나면 상한과 기본값으로 자른다.")
  @ParameterizedTest
  @CsvSource({"51, 50", "9999, 50", "0, 20", "-1, 20"})
  void size_isOutOfRange(int requested, int expected) {
    assertThat(withSize(requested).size()).isEqualTo(expected);
  }

  @DisplayName("공백뿐인 키워드는 필터로 치지 않는다.")
  @ParameterizedTest
  @CsvSource(
      value = {"''", "'   '", "NULL"},
      nullValues = "NULL")
  void keyword_isBlank(String keyword) {
    EventQuery query = new EventQuery(null, null, null, null, keyword, null, 20);

    assertThat(query.keyword()).isNull();
  }

  @DisplayName("키워드 앞뒤 공백은 떼어낸다.")
  @Test
  void keyword() {
    EventQuery query = new EventQuery(null, null, null, null, "  아이브  ", null, 20);

    assertThat(query.keyword()).isEqualTo("아이브");
  }

  @DisplayName("커서가 없으면 첫 페이지다.")
  @Test
  void hasCursor_isFirstPage() {
    assertThat(withSize(20).hasCursor()).isFalse();
  }

  @DisplayName("커서가 있으면 이어지는 페이지다.")
  @Test
  void hasCursor() {
    EventQuery query =
        new EventQuery(
            null, null, null, null, null, EventCursor.of(LocalDate.of(2026, 9, 14), 1L), 20);

    assertThat(query.hasCursor()).isTrue();
  }
}
