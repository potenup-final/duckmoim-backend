package com.duckmoim.safety.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * I-14 의 판정 (제재 중인 유저의 쓰기 차단).
 *
 * <p><b>네 종류를 전부 본다.</b> 대표만 고르면 {@code WARNED} 가 빠지는데, 하필 그것만 「쓰기 가능」이라 규칙이 뒤집혀도 초록불이 난다.
 */
class SanctionPolicyTest {

  private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 9, 1, 0, 0);
  private static final LocalDateTime NOW = ISSUED_AT.plusDays(1);

  private static final long USER_ID = 2L;

  private final SanctionPolicy policy = new SanctionPolicy();

  /** 「제재가 없다」는 행이 없는 것으로 표현된다 — 빈 목록이라 호출부에 null 분기가 없다. */
  @DisplayName("제재가 없으면 쓸 수 있다.")
  @Test
  void canWrite_hasNoSanction() {
    assertThat(policy.canWrite(List.of(), NOW)).isTrue();
  }

  @DisplayName("경고 중에는 쓸 수 있다.")
  @Test
  void canWrite_isWarned() {
    assertThat(policy.canWrite(List.of(sanction(SanctionKind.WARNED, null)), NOW)).isTrue();
  }

  @DisplayName("나이 확인 · 기간 정지 · 영구 정지 중에는 쓸 수 없다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = SanctionKind.class,
      names = {"AGE_HOLD", "SUSPENDED", "BANNED"})
  void canWrite_isBlocked(SanctionKind kind) {
    LocalDateTime until = kind.hasUntil() ? ISSUED_AT.plusDays(3) : null;

    assertThat(policy.canWrite(List.of(sanction(kind, until)), NOW)).isFalse();
  }

  @DisplayName("기간이 지난 정지는 쓰기를 막지 않는다.")
  @Test
  void canWrite_isExpiredSuspension() {
    Sanction expired = sanction(SanctionKind.SUSPENDED, ISSUED_AT.plusDays(3));

    assertThat(policy.canWrite(List.of(expired), ISSUED_AT.plusDays(4))).isTrue();
  }

  @DisplayName("풀린 제재는 쓰기를 막지 않는다.")
  @Test
  void canWrite_isReleased() {
    Sanction released = sanction(SanctionKind.BANNED, null);
    released.releaseBy(NOW);

    assertThat(policy.canWrite(List.of(released), NOW.plusDays(1))).isTrue();
  }

  /**
   * <b>가장 최근 것이 아니라 가장 강한 것이 이긴다.</b>
   *
   * <p>저장소가 {@code issuedAt DESC} 로 주므로, 한 건만 보면 나중에 걸린 경고가 앞선 정지를 가려 <b>정지 중인 유저가 통과한다.</b> 목록을 받는
   * 이유가 이 케이스다.
   */
  @DisplayName("정지와 경고가 함께 활성이면 쓸 수 없다.")
  @Test
  void canWrite_hasBlockingSanctionAmongMany() {
    Sanction warned = sanction(SanctionKind.WARNED, null);
    Sanction suspended = sanction(SanctionKind.SUSPENDED, ISSUED_AT.plusDays(3));

    assertThat(policy.canWrite(List.of(warned, suspended), NOW)).isFalse();
  }

  private static Sanction sanction(SanctionKind kind, LocalDateTime until) {
    return Sanction.of(USER_ID, kind, "사유", ISSUED_AT, until);
  }
}
