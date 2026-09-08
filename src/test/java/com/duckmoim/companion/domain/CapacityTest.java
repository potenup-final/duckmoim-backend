package com.duckmoim.companion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.exception.PostErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 정원의 검증 기준 (PO-05 · I-03). */
class CapacityTest {

  @DisplayName("정원이 2~6이면 그대로 만들어진다.")
  @ParameterizedTest
  @ValueSource(ints = {2, 3, 4, 5, 6})
  void of(int value) {
    assertThat(Capacity.of(value).getValue()).isEqualTo(value);
  }

  @DisplayName("정원을 넣지 않으면 정원이 없는 모집글이 된다.")
  @Test
  void of_valueIsAbsent() {
    assertThat(Capacity.of(null)).isNull();
  }

  @DisplayName("정원이 2~6 밖이면 만들 수 없다.")
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 7, 100})
  void of_valueIsOutOfRange(int value) {
    assertThatThrownBy(() -> Capacity.of(value))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(PostErrorCode.POST_CAPACITY_OUT_OF_RANGE);
  }

  @DisplayName("정원이 없으면 응답으로 옮길 값도 없다.")
  @Test
  void valueOf_capacityIsAbsent() {
    assertThat(Capacity.valueOf(null)).isNull();
  }

  @DisplayName("정원이 있으면 그 값이 응답으로 옮겨진다.")
  @Test
  void valueOf() {
    assertThat(Capacity.valueOf(Capacity.of(4))).isEqualTo(4);
  }
}
