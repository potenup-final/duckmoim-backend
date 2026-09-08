package com.duckmoim.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 감사 로그 목록의 커서 (AD-05).
 *
 * <p>다른 커서와 같은 계약을 지킨다 — 불투명하고, 정확하고, 판독 실패를 예외로 올린다. 상위가 그것을 INVALID_INPUT 400 으로 옮긴다 (API-컨벤션.md
 * 「Validation 규칙」).
 */
class AuditLogCursorTest {

  private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 4, 1, 0);

  @DisplayName("인코딩한 커서를 다시 읽으면 같은 값이다.")
  @Test
  void encodeAndDecode() {
    AuditLogCursor cursor = new AuditLogCursor(AT, 9L);

    assertThat(AuditLogCursor.decode(cursor.encode())).isEqualTo(cursor);
  }

  @DisplayName("커서에 정렬 키가 그대로 드러나지 않는다.")
  @Test
  void encodeIsOpaque() {
    String encoded = new AuditLogCursor(AT, 9L).encode();

    assertThat(encoded).doesNotContain("2026", "|");
  }

  @DisplayName("URL 에 그대로 실을 수 있다.")
  @Test
  void encodeIsUrlSafe() {
    String encoded = new AuditLogCursor(AT, 9L).encode();

    assertThat(encoded).matches("[A-Za-z0-9_-]+");
  }

  @DisplayName("판독할 수 없는 커서는 거절한다.")
  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"", "   ", "!!not-base64!!", "MjAyNi0wOS0wNFQwMTowMA", "fHwx"})
  void decodeIsBroken(String broken) {
    assertThatThrownBy(() -> AuditLogCursor.decode(broken))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @DisplayName("커서는 at 과 id 를 모두 갖는다.")
  @Test
  void requiresBothKeys() {
    assertThatThrownBy(() -> new AuditLogCursor(null, 9L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AuditLogCursor(AT, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
