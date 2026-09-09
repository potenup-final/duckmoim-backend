package com.duckmoim.safety.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 신고 처리 상태의 전이 규칙 (AD-03 · 도메인-모델링.md 「6. 라이프사이클」).
 *
 * <p><b>아홉 조합을 전부 본다.</b> 상태가 셋이라 조합이 아홉이고, 대표만 고르면 허용해선 안 될 전이가 조용히 열린다. 특히 되돌리기와 제자리 전이가 그렇다 —
 * 그림만 보면 「없는 화살표」라 눈에 안 띈다.
 */
class ReportStatusTest {

  private static Stream<Arguments> transitions() {
    return Stream.of(
        Arguments.of(ReportStatus.PENDING, ReportStatus.PENDING, false),
        Arguments.of(ReportStatus.PENDING, ReportStatus.PROCESSING, true),
        Arguments.of(ReportStatus.PENDING, ReportStatus.RESOLVED, true),
        Arguments.of(ReportStatus.PROCESSING, ReportStatus.PENDING, false),
        Arguments.of(ReportStatus.PROCESSING, ReportStatus.PROCESSING, false),
        Arguments.of(ReportStatus.PROCESSING, ReportStatus.RESOLVED, true),
        Arguments.of(ReportStatus.RESOLVED, ReportStatus.PENDING, false),
        Arguments.of(ReportStatus.RESOLVED, ReportStatus.PROCESSING, false),
        Arguments.of(ReportStatus.RESOLVED, ReportStatus.RESOLVED, false));
  }

  @DisplayName("전이 아홉 조합 중 셋만 허용한다.")
  @ParameterizedTest(name = "{0} → {1} = {2}")
  @MethodSource("transitions")
  void canMoveTo(ReportStatus from, ReportStatus next, boolean allowed) {
    assertThat(from.canMoveTo(next)).isEqualTo(allowed);
  }
}
