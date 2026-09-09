package com.duckmoim.companion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.common.exception.CommonErrorCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 만남 지점의 검증 기준 (PO-03 · I-05). */
class MeetPointTest {

  private static final BigDecimal LAT = new BigDecimal("37.5256381");
  private static final BigDecimal LNG = new BigDecimal("126.9289384");

  @DisplayName("장소명과 좌표를 모두 주면 만남 지점이 만들어진다.")
  @Test
  void of() {
    MeetPoint meetPoint = MeetPoint.of("더현대 서울 지하 1층", LAT, LNG);

    assertThat(meetPoint.getPlace()).isEqualTo("더현대 서울 지하 1층");
    assertThat(meetPoint.getLat()).isEqualTo(LAT);
    assertThat(meetPoint.getLng()).isEqualTo(LNG);
  }

  @DisplayName("좌표 없이는 만남 지점을 만들 수 없다.")
  @Test
  void of_coordinateIsMissing() {
    assertThatThrownBy(() -> MeetPoint.of("더현대 서울 지하 1층", null, LNG))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(CommonErrorCode.INVALID_INPUT);
  }

  @DisplayName("장소명 없이는 만남 지점을 만들 수 없다.")
  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void of_placeIsBlank(String place) {
    assertThatThrownBy(() -> MeetPoint.of(place, LAT, LNG))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(CommonErrorCode.INVALID_INPUT);
  }
}
