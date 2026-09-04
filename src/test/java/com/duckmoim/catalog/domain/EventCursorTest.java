package com.duckmoim.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EventCursorTest {

  @DisplayName("커서를 인코딩한 뒤 디코딩하면 원래 정렬 키가 나온다.")
  @Test
  void decode() {
    // given
    EventCursor cursor = EventCursor.of(LocalDate.of(2026, 9, 14), 42L);

    // when
    EventCursor decoded = EventCursor.decode(cursor.encode());

    // then
    assertThat(decoded).isEqualTo(cursor);
  }

  @DisplayName("커서가 클라이언트에게 불투명하다.")
  @Test
  void encode_isOpaque() {
    // given
    EventCursor cursor = EventCursor.of(LocalDate.of(2026, 9, 14), 42L);

    // when
    String encoded = cursor.encode();

    // then
    assertThat(encoded).doesNotContain("2026-09-14").doesNotContain("42");
  }

  @DisplayName("커서에 URL 에 쓸 수 없는 문자가 들어가지 않는다.")
  @Test
  void encode_isUrlSafe() {
    // given
    EventCursor cursor = EventCursor.of(LocalDate.of(2026, 9, 14), 9_999_999L);

    // when
    String encoded = cursor.encode();

    // then
    assertThat(encoded).matches("[A-Za-z0-9_-]+");
  }

  @DisplayName("망가진 커서는 판독할 수 없다.")
  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "!!!", "MjAyNi0wOS0xNA", "MjAyNi0wOS0xNHw", "fDQy"})
  void decode_isBroken(String broken) {
    assertThatThrownBy(() -> EventCursor.decode(broken))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @DisplayName("커서 값이 날짜나 숫자가 아니면 판독할 수 없다.")
  @Test
  void decode_hasWrongType() {
    // given — 형식은 맞지만 값이 날짜가 아닌 "어제|42"
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("어제|42".getBytes(StandardCharsets.UTF_8));

    // when & then
    assertThatThrownBy(() -> EventCursor.decode(encoded))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @DisplayName("커서는 startsOn 과 id 를 모두 가져야 만들어진다.")
  @Test
  void of_hasNullKey() {
    assertThatThrownBy(() -> EventCursor.of(null, 42L))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
