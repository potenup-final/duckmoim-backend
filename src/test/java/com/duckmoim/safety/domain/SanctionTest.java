package com.duckmoim.safety.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.safety.exception.SanctionErrorCode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 제재의 도메인 규칙 (AD-04 · I-14).
 *
 * <p>「제재 = 차단」으로 뭉뚱그리면 {@code WARNED} 행이 깨진다. 도메인-모델링.md 「6. 라이프사이클」의 제재 축 표가 그 행을 <b>쓰기 가능</b>으로
 * 정했고, 같은 문서가 <i>"막을 것이면 정지를 준다"</i> 고 적었다.
 */
class SanctionTest {

  private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 9, 1, 0, 0);

  private static final long USER_ID = 2L;

  @DisplayName("제재를 걸면 사유와 발효 시각이 남는다.")
  @Test
  void of() {
    Sanction sanction = warned();

    assertThat(sanction.getUserId()).isEqualTo(USER_ID);
    assertThat(sanction.getKind()).isEqualTo(SanctionKind.WARNED);
    assertThat(sanction.getReason()).isEqualTo("약속 불이행 신고가 세 건 접수되었습니다");
    assertThat(sanction.getIssuedAt()).isEqualTo(ISSUED_AT);
    assertThat(sanction.getReleasedAt()).isNull();
  }

  /** 본인에게 보여주는 정보라 비면 안내 화면이 이유 없이 뜬다. */
  @DisplayName("사유는 비울 수 없다.")
  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void of_hasBlankReason(String reason) {
    assertThatThrownBy(() -> Sanction.of(USER_ID, SanctionKind.WARNED, reason, ISSUED_AT, null))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(SanctionErrorCode.SANCTION_REASON_REQUIRED);
  }

  @DisplayName("사유가 없으면 제재를 걸 수 없다.")
  @Test
  void of_hasNoReason() {
    assertThatThrownBy(() -> Sanction.of(USER_ID, SanctionKind.WARNED, null, ISSUED_AT, null))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(SanctionErrorCode.SANCTION_REASON_REQUIRED);
  }

  /** 조용히 버리면 관리자는 기간을 준 줄 알고 화면에는 안 나온다. */
  @DisplayName("기간 정지가 아닌데 해제 시각을 주면 400 이다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = SanctionKind.class,
      names = {"WARNED", "AGE_HOLD", "BANNED"})
  void of_hasUntilWithoutSuspension(SanctionKind kind) {
    assertThatThrownBy(() -> Sanction.of(USER_ID, kind, "사유", ISSUED_AT, ISSUED_AT.plusDays(3)))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(SanctionErrorCode.SANCTION_UNTIL_MISMATCH);
  }

  /** 기간 정지인데 끝이 없으면 영구 정지와 구분되지 않는다. */
  @DisplayName("기간 정지인데 해제 시각이 없으면 400 이다.")
  @Test
  void of_isSuspensionWithoutUntil() {
    assertThatThrownBy(() -> Sanction.of(USER_ID, SanctionKind.SUSPENDED, "사유", ISSUED_AT, null))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(SanctionErrorCode.SANCTION_UNTIL_MISMATCH);
  }

  @DisplayName("관리자가 제재를 풀면 푼 시각이 남는다.")
  @Test
  void releaseBy() {
    Sanction sanction = warned();

    sanction.releaseBy(ISSUED_AT.plusDays(1));

    assertThat(sanction.getReleasedAt()).isEqualTo(ISSUED_AT.plusDays(1));
    assertThat(sanction.isActiveAt(ISSUED_AT.plusDays(2))).isFalse();
  }

  @DisplayName("이미 푼 제재는 다시 풀 수 없다.")
  @Test
  void releaseBy_isAlreadyReleased() {
    Sanction sanction = warned();
    sanction.releaseBy(ISSUED_AT.plusDays(1));

    assertThatThrownBy(() -> sanction.releaseBy(ISSUED_AT.plusDays(2)))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(SanctionErrorCode.SANCTION_ALREADY_RELEASED);
  }

  @DisplayName("기간 정지는 until 이 지나면 스스로 풀린다.")
  @Test
  void isActiveAt_suspensionExpires() {
    Sanction sanction =
        Sanction.of(USER_ID, SanctionKind.SUSPENDED, "사유", ISSUED_AT, ISSUED_AT.plusDays(3));

    assertThat(sanction.isActiveAt(ISSUED_AT.plusDays(2))).isTrue();
    assertThat(sanction.isActiveAt(ISSUED_AT.plusDays(3))).isFalse();
  }

  /** 도메인 6장 제재 축 표가 「조치일로부터 1년」으로 정했다. */
  @DisplayName("경고는 1년이 지나면 스스로 풀린다.")
  @Test
  void isActiveAt_warningExpires() {
    Sanction sanction = warned();

    assertThat(sanction.isActiveAt(ISSUED_AT.plusYears(1).minusDays(1))).isTrue();
    assertThat(sanction.isActiveAt(ISSUED_AT.plusYears(1))).isFalse();
  }

  @DisplayName("나이 확인과 영구 정지는 스스로 풀리지 않는다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = SanctionKind.class,
      names = {"AGE_HOLD", "BANNED"})
  void isActiveAt_neverExpires(SanctionKind kind) {
    Sanction sanction = Sanction.of(USER_ID, kind, "사유", ISSUED_AT, null);

    assertThat(sanction.isActiveAt(ISSUED_AT.plusYears(100))).isTrue();
  }

  /** I-14 가 걸린 자리다. 도메인 6장 제재 축 표의 「쓰기」 열 그대로다. */
  @DisplayName("경고만 쓰기를 막지 않는다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(SanctionKind.class)
  void blocksWriting(SanctionKind kind) {
    assertThat(kind.blocksWriting()).isEqualTo(kind != SanctionKind.WARNED);
  }

  @DisplayName("만료된 제재는 쓰기를 막지 않는다.")
  @Test
  void blocksWritingAt_isExpired() {
    Sanction sanction =
        Sanction.of(USER_ID, SanctionKind.SUSPENDED, "사유", ISSUED_AT, ISSUED_AT.plusDays(3));

    assertThat(sanction.blocksWritingAt(ISSUED_AT.plusDays(2))).isTrue();
    assertThat(sanction.blocksWritingAt(ISSUED_AT.plusDays(3))).isFalse();
  }

  @DisplayName("경고는 유효해도 쓰기를 막지 않는다.")
  @Test
  void blocksWritingAt_isWarning() {
    Sanction sanction = warned();

    assertThat(sanction.isActiveAt(ISSUED_AT.plusDays(1))).isTrue();
    assertThat(sanction.blocksWritingAt(ISSUED_AT.plusDays(1))).isFalse();
  }

  private static Sanction warned() {
    return Sanction.of(USER_ID, SanctionKind.WARNED, "약속 불이행 신고가 세 건 접수되었습니다", ISSUED_AT, null);
  }
}
