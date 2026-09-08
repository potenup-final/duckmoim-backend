package com.duckmoim.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 구간 경계 (도메인-모델링.md 「7.2 최근 접속일 노출」).
 *
 * <p>네 경계(24시간 · 3일 · 7일 · 30일)의 <b>양쪽</b>을 본다. 경계가 「이내」라 안쪽은 좁은 구간에 들고 1초만 지나도 다음 구간으로 넘어가야 한다.
 * 한쪽만 보면 부등호가 뒤집혀도 초록불이 난다.
 *
 * <p>시계를 고정한다. 경과 시간으로 갈리는 값이라 놓아두면 같은 데이터가 어제와 오늘 다르게 나온다.
 */
class LastSeenTest {

  private static final LocalDateTime NOW_UTC = LocalDateTime.of(2026, 9, 8, 3, 0);

  private static final Clock FIXED =
      Clock.fixed(NOW_UTC.toInstant(ZoneOffset.UTC), ZoneId.of("Asia/Seoul"));

  @DisplayName("경과 시간이 구간을 정한다.")
  @ParameterizedTest(name = "{0} 전 → {1}")
  @MethodSource("boundaries")
  void from(Duration elapsed, LastSeen expected) {
    LocalDateTime lastSeenAt = NOW_UTC.minus(elapsed);

    assertThat(LastSeen.from(lastSeenAt, FIXED)).isEqualTo(expected);
  }

  /** 경계마다 안쪽 끝과 그 1초 뒤를 짝으로 놓는다. */
  private static Stream<Arguments> boundaries() {
    return Stream.of(
        Arguments.of(Duration.ZERO, LastSeen.TODAY),
        Arguments.of(Duration.ofHours(24), LastSeen.TODAY),
        Arguments.of(Duration.ofHours(24).plusSeconds(1), LastSeen.WITHIN_3_DAYS),
        Arguments.of(Duration.ofDays(3), LastSeen.WITHIN_3_DAYS),
        Arguments.of(Duration.ofDays(3).plusSeconds(1), LastSeen.WITHIN_WEEK),
        Arguments.of(Duration.ofDays(7), LastSeen.WITHIN_WEEK),
        Arguments.of(Duration.ofDays(7).plusSeconds(1), LastSeen.WITHIN_MONTH),
        Arguments.of(Duration.ofDays(30), LastSeen.WITHIN_MONTH),
        Arguments.of(Duration.ofDays(30).plusSeconds(1), LastSeen.LONG_AGO),
        Arguments.of(Duration.ofDays(365), LastSeen.LONG_AGO));
  }

  /**
   * 로그인 후 토큰을 한 번도 재발급하지 않은 계정이다 (AU-03 이 재발급 시점에만 갱신한다).
   *
   * <p>없는 것을 {@code LONG_AGO} 로 적으면 방금 가입한 사람이 한 달 넘게 활동이 없는 것으로 보인다.
   */
  @DisplayName("관측된 적이 없으면 구간도 없다.")
  @Test
  void from_hasNeverBeenSeen() {
    assertThat(LastSeen.from(null, FIXED)).isNull();
  }
}
