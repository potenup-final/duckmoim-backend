package com.duckmoim.safety.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 제재 목록 커서의 왕복과 판독 실패 (AD-10).
 *
 * <p>정렬과 페이지 경계는 저장소 통합 테스트가 본다. 여기서는 <b>인코딩한 것이 그대로 돌아오는지</b>와 <b>망가진 입력이 예외가 되는지</b>만 본다.
 */
class SanctionCursorTest {

  private static final LocalDateTime AT_UTC = LocalDateTime.of(2026, 8, 31, 0, 12);

  @DisplayName("인코딩한 커서를 그대로 판독한다.")
  @Test
  void encodeThenDecode() {
    SanctionCursor cursor = new SanctionCursor(AT_UTC, 5L);

    assertThat(SanctionCursor.decode(cursor.encode())).isEqualTo(cursor);
  }

  /**
   * 만료 없는 제재({@code AGE_HOLD} · {@code BANNED})를 가리키는 커서다.
   *
   * <p>이 왕복이 깨지면 목록 뒤쪽 구간에서 이어 읽기가 처음으로 돌아가 같은 페이지를 무한히 준다.
   */
  @DisplayName("만료가 비어 있는 커서도 그대로 판독한다.")
  @Test
  void encodeThenDecode_hasNoExpiry() {
    SanctionCursor cursor = new SanctionCursor(null, 5L);

    assertThat(cursor.hasExpiry()).isFalse();
    assertThat(SanctionCursor.decode(cursor.encode())).isEqualTo(cursor);
  }

  /** 클라이언트가 값을 해석하고 의존하는 것을 막는 장치다 (API 컨벤션). */
  @DisplayName("커서에 원본 값이 그대로 드러나지 않는다.")
  @Test
  void encodeHidesStructure() {
    assertThat(new SanctionCursor(AT_UTC, 5L).encode()).doesNotContain("2026", "|");
  }

  @DisplayName("판독할 수 없는 커서는 예외다.")
  @ParameterizedTest
  @ValueSource(strings = {"!!broken!!", "", "   "})
  void decode_isBroken(String encoded) {
    assertThatThrownBy(() -> SanctionCursor.decode(encoded))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @DisplayName("null 커서는 예외다.")
  @Test
  void decode_isNull() {
    assertThatThrownBy(() -> SanctionCursor.decode(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 조각이 하나로 줄면 id 가 사라진 채 통과할 수 있다. split 의 limit 이 그것을 막는다. */
  @DisplayName("뒤가 빈 커서는 예외다.")
  @Test
  void decode_hasEmptyTail() {
    assertThatThrownBy(() -> SanctionCursor.decode(encoded(AT_UTC + "|")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 만료 자리가 비는 것은 정상이지만 그 값이 시각이 아닌 것은 아니다. */
  @DisplayName("만료 자리에 시각이 아닌 값이 오면 예외다.")
  @Test
  void decode_hasBrokenExpiry() {
    assertThatThrownBy(() -> SanctionCursor.decode(encoded("어제|5")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @DisplayName("id 가 없는 커서는 만들 수 없다.")
  @Test
  void requiresId() {
    assertThatThrownBy(() -> new SanctionCursor(AT_UTC, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static String encoded(String plain) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
  }
}
