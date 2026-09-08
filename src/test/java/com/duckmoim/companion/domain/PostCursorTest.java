package com.duckmoim.companion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 커서는 <b>클라이언트에게 불투명하고, 서버에게는 정확해야 한다</b> (PO-08).
 *
 * <p>판독 실패를 예외로 올리는 것이 계약이다 — 상위가 그것을 INVALID_INPUT 400 으로 옮긴다 (API 컨벤션 「Validation 규칙」).
 */
class PostCursorTest {

  private static final LocalDateTime MEET_AT = LocalDateTime.of(2026, 9, 14, 0, 0);

  @DisplayName("커서는 만남시각과 id 를 함께 담는다.")
  @Test
  void encodeAndDecode() {
    PostCursor cursor = new PostCursor(MEET_AT, 12L);

    assertThat(PostCursor.decode(cursor.encode())).isEqualTo(cursor);
  }

  @DisplayName("커서에 정렬 키가 그대로 드러나지 않는다.")
  @Test
  void encode_isOpaque() {
    String encoded = new PostCursor(MEET_AT, 12L).encode();

    assertThat(encoded).doesNotContain("2026", "|");
  }

  @DisplayName("URL 에 그대로 실을 수 있다.")
  @Test
  void encode_isUrlSafe() {
    String encoded = new PostCursor(MEET_AT, 12L).encode();

    assertThat(encoded).matches("[A-Za-z0-9_-]+");
  }

  @DisplayName("형식이 어긋난 커서는 판독하지 않는다.")
  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"", "   ", "!!not-base64!!", "MjAyNi0wOS0xNFQwMDowMA", "fHwx"})
  void decode_isBroken(String broken) {
    assertThatThrownBy(() -> PostCursor.decode(broken))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @DisplayName("null 커서도 거절한다.")
  @Test
  void decode_isNull() {
    assertThatThrownBy(() -> PostCursor.decode(null)).isInstanceOf(IllegalArgumentException.class);
  }

  /** id 가 빠지면 같은 시각에 만나는 글 사이에 순서가 없어 페이지 경계에서 누락이 난다 (PO-08). */
  @DisplayName("정렬 키가 하나라도 없으면 커서가 아니다.")
  @Test
  void of_hasNullKey() {
    assertThatThrownBy(() -> new PostCursor(MEET_AT, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PostCursor(null, 12L))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
