package com.duckmoim.safety.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 제재의 두 판정 — 쓰기 차단(I-14)과 <b>비공개 읽기 차단</b>(STAR-84).
 *
 * <p><b>네 종류를 전부 본다.</b> 대표만 고르면 축이 뒤집혀도 초록불이 난다 — 쓰기는 {@code WARNED} 만, 읽기는 {@code BANNED} 만 나머지와
 * 다르게 답한다.
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

  /** 읽기 축은 여기 하나만 「불가」다. 쓰기 축과 대칭이 아니라는 것이 이 판정의 전부다. */
  @DisplayName("영구 정지 중에는 비공개 콘텐츠를 읽을 수 없다.")
  @Test
  void canReadPrivate_isBanned() {
    assertThat(policy.canReadPrivate(List.of(sanction(SanctionKind.BANNED, null)), NOW)).isFalse();
  }

  /**
   * <b>읽기 차단이 {@code BANNED} 에만 붙는지 본다.</b> 「제재 중이면 못 읽는다」로 한 줄 짜면 이 셋이 조용히 함께 막히고, 그때 정지당한 사람이 자기
   * 채팅방도 못 보게 된다 — 도메인 6장 제재 축 표의 「비공개 읽기」 열이 그 셋에 「가능」을 적어 두었다.
   */
  @DisplayName("경고 · 나이 확인 · 기간 정지 중에는 비공개 콘텐츠를 읽을 수 있다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = SanctionKind.class,
      names = {"WARNED", "AGE_HOLD", "SUSPENDED"})
  void canReadPrivate_isNotBanned(SanctionKind kind) {
    LocalDateTime until = kind.hasUntil() ? ISSUED_AT.plusDays(3) : null;

    assertThat(policy.canReadPrivate(List.of(sanction(kind, until)), NOW)).isTrue();
  }

  /** 관리자가 풀면 읽기가 돌아온다. 유효성과 곱하지 않으면 해제가 반영되지 않는다. */
  @DisplayName("풀린 영구 정지는 비공개 읽기를 막지 않는다.")
  @Test
  void canReadPrivate_isReleased() {
    Sanction released = sanction(SanctionKind.BANNED, null);
    released.releaseBy(NOW);

    assertThat(policy.canReadPrivate(List.of(released), NOW.plusDays(1))).isTrue();
  }

  @DisplayName("제재가 없으면 비공개 콘텐츠를 읽을 수 있다.")
  @Test
  void canReadPrivate_hasNoSanction() {
    assertThat(policy.canReadPrivate(List.of(), NOW)).isTrue();
  }
}
