package com.duckmoim.safety.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.safety.exception.ReportErrorCode;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 대상×사유 전 조합 (API-설계.md 「2-6. 신고 (Safety)」의 조합표).
 *
 * <p>대상 3 × 사유 7 = <b>21 조합</b>이고 허용이 14 · 거부가 7 이다. 조합표가 값 집합이자 검증 규칙이라 <b>표를 손으로 옮겨 적고 계산하지
 * 않는다</b> — 코드로 구하면 판정 로직을 테스트에 한 번 더 쓰는 것이고, 둘이 같이 틀리면 초록불이 난다.
 *
 * <p>서버가 이것을 검증하는 이유는 결정 D-6 이다. 사유 목록은 클라이언트가 갖고, <i>"화면에 무엇이 떴든 API 는 직접 호출될 수 있다."</i>
 */
class ReportReasonTest {

  private static final long REPORTER_ID = 1L;
  private static final long TARGET_ID = 42L;

  /** API 설계 조합표를 그대로 옮긴 것이다. 여기가 이 테스트의 정본이다. */
  private static final List<Arguments> ALLOWED =
      List.of(
          Arguments.of(ReportTargetType.USER, ReportReason.ADVERTISEMENT),
          Arguments.of(ReportTargetType.USER, ReportReason.INAPPROPRIATE),
          Arguments.of(ReportTargetType.USER, ReportReason.ABUSE),
          Arguments.of(ReportTargetType.USER, ReportReason.NO_SHOW),
          Arguments.of(ReportTargetType.USER, ReportReason.AGE_SUSPICION),
          Arguments.of(ReportTargetType.POST, ReportReason.ADVERTISEMENT),
          Arguments.of(ReportTargetType.POST, ReportReason.INAPPROPRIATE),
          Arguments.of(ReportTargetType.POST, ReportReason.ABUSE),
          Arguments.of(ReportTargetType.POST, ReportReason.FALSE_INFO),
          Arguments.of(ReportTargetType.POST, ReportReason.OFF_TOPIC),
          Arguments.of(ReportTargetType.COMMENT, ReportReason.ADVERTISEMENT),
          Arguments.of(ReportTargetType.COMMENT, ReportReason.INAPPROPRIATE),
          Arguments.of(ReportTargetType.COMMENT, ReportReason.ABUSE),
          Arguments.of(ReportTargetType.COMMENT, ReportReason.FALSE_INFO));

  @DisplayName("조합표에 있는 사유로 접수된다.")
  @ParameterizedTest(name = "{0} · {1}")
  @MethodSource("allowed")
  void of(ReportTargetType targetType, ReportReason reason) {
    Report report = Report.of(REPORTER_ID, targetType, TARGET_ID, reason, "상세");

    assertThat(report.getTargetType()).isEqualTo(targetType);
    assertThat(report.getReason()).isEqualTo(reason);
    assertThat(report.getStatus()).isEqualTo(ReportStatus.PENDING);
  }

  @DisplayName("조합표에 없는 사유는 그 대상에 쓸 수 없다.")
  @ParameterizedTest(name = "{0} · {1}")
  @MethodSource("rejected")
  void of_reasonDoesNotFitTarget(ReportTargetType targetType, ReportReason reason) {
    assertThatThrownBy(() -> Report.of(REPORTER_ID, targetType, TARGET_ID, reason, "상세"))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(ReportErrorCode.REPORT_REASON_INVALID);
  }

  /** 21 조합이 허용과 거부로 정확히 갈렸는지 본다. 표를 옮겨 적다 한 줄을 빠뜨리면 여기서 드러난다. */
  @DisplayName("대상과 사유의 모든 조합이 허용 또는 거부로 갈린다.")
  @Test
  void everyCombinationIsCovered() {
    int total = ReportTargetType.values().length * ReportReason.values().length;

    assertThat(ALLOWED).hasSize(14);
    assertThat(rejected().count()).isEqualTo(total - ALLOWED.size());
    assertThat(total).isEqualTo(21);
  }

  private static Stream<Arguments> allowed() {
    return ALLOWED.stream();
  }

  /** 허용 목록의 여집합이다. 거부를 손으로 또 적으면 둘이 갈라진다. */
  private static Stream<Arguments> rejected() {
    return Arrays.stream(ReportTargetType.values())
        .flatMap(
            targetType ->
                Arrays.stream(ReportReason.values())
                    .filter(reason -> !isAllowed(targetType, reason))
                    .map(reason -> Arguments.of(targetType, reason)));
  }

  private static boolean isAllowed(ReportTargetType targetType, ReportReason reason) {
    return ALLOWED.stream()
        .anyMatch(allowed -> allowed.get()[0] == targetType && allowed.get()[1] == reason);
  }
}
