package com.duckmoim.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.exception.UserErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 만 14세 미만 차단 (I-15 · AU-05).
 *
 * <p>「올해」를 고정해 검증한다 — 값 객체가 시각을 스스로 읽지 않는 이유가 이것이다. 실행 연도에 끌려가면 <b>내년에 조용히 다른 것을 검증한다.</b>
 */
class BirthYearTest {

  private static final int THIS_YEAR = 2026;

  @Test
  @DisplayName("올해에서 15를 뺀 해에 태어나면 가입할 수 있다.")
  void of_atBoundary() {
    BirthYear birthYear = BirthYear.of(THIS_YEAR - 15, THIS_YEAR);

    assertThat(birthYear.getValue()).isEqualTo(2011);
  }

  /**
   * AU-05 의 검증 기준 그 자체 — <b>경계 연도(올해−14) 입력 시 가입 거부.</b>
   *
   * <p>{@code 올해 − 출생연도 = 14} 라서 딱 한 해 모자란 자리다. 부등호를 {@code >} 로 잘못 쓰면 여기서만 빨간불이 난다.
   */
  @Test
  @DisplayName("올해에서 14를 뺀 해에 태어나면 가입이 거부된다.")
  void of_oneYearShort() {
    assertThatThrownBy(() -> BirthYear.of(THIS_YEAR - 14, THIS_YEAR))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_UNDER_MINIMUM_AGE);
  }

  @ParameterizedTest
  @ValueSource(ints = {16, 20, 60})
  @DisplayName("경계보다 이른 해에 태어나면 가입할 수 있다.")
  void of_olderThanBoundary(int yearGap) {
    assertThat(BirthYear.of(THIS_YEAR - yearGap, THIS_YEAR)).isNotNull();
  }

  @ParameterizedTest
  @ValueSource(ints = {13, 5, 0})
  @DisplayName("경계보다 늦은 해에 태어나면 가입이 거부된다.")
  void of_youngerThanBoundary(int yearGap) {
    assertThatThrownBy(() -> BirthYear.of(THIS_YEAR - yearGap, THIS_YEAR))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_UNDER_MINIMUM_AGE);
  }

  /** 판정이 뺄셈이라 미래 연도는 저절로 걸린다 — 별도 검사를 두지 않는 이유다. */
  @Test
  @DisplayName("아직 오지 않은 해에 태어났다고 하면 가입이 거부된다.")
  void of_futureYear() {
    assertThatThrownBy(() -> BirthYear.of(THIS_YEAR + 1, THIS_YEAR))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.USER_UNDER_MINIMUM_AGE);
  }
}
