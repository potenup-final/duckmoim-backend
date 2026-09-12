package com.duckmoim.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 목록 크기의 상·하한 (CH-09).
 *
 * <p>범위를 벗어난 값을 거절하지 않고 자른다 — API-설계.md 「검증 상한」이 목록 {@code size} 를 <i>1~50 으로 조용히 맞춘다</i> 로 정했다.
 */
@DisplayName("메시지 목록 조건")
class MessageListQueryTest {

  @DisplayName("상한을 넘는 크기는 상한으로 잘린다.")
  @Test
  void clampsAboveMax() {
    assertThat(new MessageListQuery(1L, null, 51).size()).isEqualTo(MessageListQuery.MAX_SIZE);
  }

  @DisplayName("0 이하의 크기는 기본값이 된다.")
  @ParameterizedTest(name = "size={0}")
  @ValueSource(ints = {0, -1})
  void fallsBackToDefault(int size) {
    assertThat(new MessageListQuery(1L, null, size).size())
        .isEqualTo(MessageListQuery.DEFAULT_SIZE);
  }

  @DisplayName("범위 안의 크기는 그대로 쓴다.")
  @Test
  void keepsValidSize() {
    assertThat(new MessageListQuery(1L, null, 10).size()).isEqualTo(10);
  }

  @DisplayName("커서가 없으면 첫 페이지다.")
  @Test
  void hasNoCursorOnFirstPage() {
    assertThat(new MessageListQuery(1L, null, 10).hasCursor()).isFalse();
  }
}
