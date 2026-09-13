package com.duckmoim.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 알림함의 커서 (NT-08).
 *
 * <p>다른 커서들과 같은 계약을 지킨다 — 불투명하고, 정확하고, 판독 실패를 예외로 올린다. 상위가 그것을 INVALID_INPUT 400 으로 옮긴다 (API-컨벤션.md
 * 「Validation 규칙」).
 */
class NotificationCursorTest {

  private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 14, 0, 0);

  @DisplayName("인코딩한 커서를 다시 읽으면 같은 값이다.")
  @Test
  void encodeAndDecode() {
    NotificationCursor cursor = new NotificationCursor(CREATED_AT, 42L);

    assertThat(NotificationCursor.decode(cursor.encode())).isEqualTo(cursor);
  }

  /**
   * 마이크로초까지 살아 있어야 한다.
   *
   * <p>워커가 한 주기에 여러 건을 보내면 같은 초에 알림이 몰린다. 커서가 초 단위로 잘리면 그 안의 순서를 잃고, 이어 읽을 때 같은 초의 알림이 통째로 건너뛰어진다.
   */
  @DisplayName("마이크로초가 커서를 오가도 남는다.")
  @Test
  void encodeAndDecode_keepsMicros() {
    NotificationCursor cursor = new NotificationCursor(CREATED_AT.withNano(123_456_000), 42L);

    assertThat(NotificationCursor.decode(cursor.encode())).isEqualTo(cursor);
  }

  @DisplayName("커서에 정렬 키가 그대로 드러나지 않는다.")
  @Test
  void encode_isOpaque() {
    String encoded = new NotificationCursor(CREATED_AT, 42L).encode();

    assertThat(encoded).doesNotContain("2026", "|");
  }

  @DisplayName("URL 에 그대로 실을 수 있다.")
  @Test
  void encode_isUrlSafe() {
    String encoded = new NotificationCursor(CREATED_AT, 42L).encode();

    assertThat(encoded).matches("[A-Za-z0-9_-]+");
  }

  @DisplayName("판독할 수 없는 커서는 거절한다.")
  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"", "   ", "!!not-base64!!", "MjAyNi0wOS0xNFQwOTowMA", "fHwx"})
  void decode_isBroken(String broken) {
    assertThatThrownBy(() -> NotificationCursor.decode(broken))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @DisplayName("null 커서도 거절한다.")
  @Test
  void decode_isNull() {
    assertThatThrownBy(() -> NotificationCursor.decode(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @DisplayName("정렬 키가 하나라도 없으면 커서가 아니다.")
  @Test
  void of_hasNullKey() {
    assertThatThrownBy(() -> new NotificationCursor(CREATED_AT, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new NotificationCursor(null, 42L))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
