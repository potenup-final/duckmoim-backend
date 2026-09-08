package com.duckmoim.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 감사 로그 목록의 조회 조건 (AD-05). */
class AuditLogListQueryTest {

  @DisplayName("size 가 범위 안이면 그대로 쓴다.")
  @ParameterizedTest(name = "{0}")
  @ValueSource(ints = {1, 20, 50})
  void sizeInRange(int size) {
    assertThat(new AuditLogListQuery(null, size).size()).isEqualTo(size);
  }

  @DisplayName("size 가 1 보다 작으면 기본값으로 돌린다.")
  @ParameterizedTest(name = "{0}")
  @ValueSource(ints = {0, -1})
  void sizeBelowRange(int size) {
    assertThat(new AuditLogListQuery(null, size).size()).isEqualTo(AuditLogListQuery.DEFAULT_SIZE);
  }

  @DisplayName("size 가 상한을 넘으면 상한으로 자른다.")
  @Test
  void sizeAboveRange() {
    assertThat(new AuditLogListQuery(null, 1000).size()).isEqualTo(AuditLogListQuery.MAX_SIZE);
  }

  @DisplayName("커서가 없으면 첫 페이지다.")
  @Test
  void withoutCursor() {
    assertThat(new AuditLogListQuery(null, 20).hasCursor()).isFalse();
  }

  @DisplayName("커서가 있으면 이어 읽는다.")
  @Test
  void withCursor() {
    AuditLogCursor cursor = new AuditLogCursor(LocalDateTime.of(2026, 9, 4, 1, 0), 9L);

    assertThat(new AuditLogListQuery(cursor, 20).hasCursor()).isTrue();
  }
}
