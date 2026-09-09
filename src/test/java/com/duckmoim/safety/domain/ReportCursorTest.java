package com.duckmoim.safety.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 신고 목록 커서의 왕복과 판독 실패 (AD-02).
 *
 * <p>정렬과 페이지 경계는 저장소 통합 테스트가 본다. 여기서는 <b>인코딩한 것이 그대로 돌아오는지</b>와 <b>망가진 입력이 예외가 되는지</b>만 본다.
 */
class ReportCursorTest {

  private static final LocalDateTime AT_UTC = LocalDateTime.of(2026, 8, 31, 0, 12);

  @DisplayName("인코딩한 커서를 그대로 판독한다.")
  @Test
  void encodeThenDecode() {
    ReportCursor cursor = new ReportCursor(AT_UTC, 5L);

    assertThat(ReportCursor.decode(cursor.encode())).isEqualTo(cursor);
  }

  /** 클라이언트가 값을 해석하고 의존하는 것을 막는 장치다 (API 컨벤션). */
  @DisplayName("커서에 원본 값이 그대로 드러나지 않는다.")
  @Test
  void encodeHidesStructure() {
    assertThat(new ReportCursor(AT_UTC, 5L).encode()).doesNotContain("2026", "|");
  }

  @DisplayName("판독할 수 없는 커서는 예외다.")
  @ParameterizedTest
  @ValueSource(strings = {"!!broken!!", "", "   "})
  void decode_isBroken(String encoded) {
    assertThatThrownBy(() -> ReportCursor.decode(encoded))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @DisplayName("null 커서는 예외다.")
  @Test
  void decode_isNull() {
    assertThatThrownBy(() -> ReportCursor.decode(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 조각이 하나로 줄면 id 가 사라진 채 통과할 수 있다. split 의 limit 이 그것을 막는다. */
  @DisplayName("뒤가 빈 커서는 예외다.")
  @Test
  void decode_hasEmptyTail() {
    String broken =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString((AT_UTC + "|").getBytes(java.nio.charset.StandardCharsets.UTF_8));

    assertThatThrownBy(() -> ReportCursor.decode(broken))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @DisplayName("커서는 createdAt 과 id 를 모두 갖는다.")
  @Test
  void requiresBoth() {
    assertThatThrownBy(() -> new ReportCursor(AT_UTC, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReportCursor(null, 5L))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
