package com.duckmoim.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 메시지 목록의 커서 (CH-09).
 *
 * <p>다른 커서들과 같은 계약을 지킨다 — 불투명하고, 정확하고, 판독 실패를 예외로 올린다. 상위가 그것을 INVALID_INPUT 400 으로 옮긴다 (API-컨벤션.md
 * 「Validation 규칙」).
 *
 * <p><b>값이 하나라 여기 없는 검사가 있다.</b> {@code NotificationCursorTest} 는 「마이크로초가 살아남는가」를 보는데, 그건 정렬키가
 * {@code createdAt} 이라 정밀도를 잃으면 순서를 잃기 때문이다. {@code id} 는 잃을 정밀도가 없다.
 */
@DisplayName("메시지 커서")
class MessageCursorTest {

  @DisplayName("인코딩한 커서를 다시 읽으면 같은 값이다.")
  @Test
  void encodeAndDecode() {
    MessageCursor cursor = new MessageCursor(51L);

    assertThat(MessageCursor.decode(cursor.encode())).isEqualTo(cursor);
  }

  /**
   * 불투명해야 하는 이유가 여기 있다.
   *
   * <p>커서가 메시지 번호 그대로면 클라이언트가 그 값을 읽고 계산하기 시작한다. 그러면 <b>나중에 {@code (createdAt, id)} 로 바꿔야 할 때 못
   * 바꾼다</b> — 지금 하나로 둔 결정을 되돌릴 여지를 남기는 것이 이 인코딩의 값이다.
   */
  @DisplayName("커서에 메시지 번호가 그대로 드러나지 않는다.")
  @Test
  void encode_isOpaque() {
    assertThat(new MessageCursor(51L).encode()).doesNotContain("51");
  }

  /** 큰 번호도 오간다. BIGINT 라 int 로 좁히면 여기서 깨진다. */
  @DisplayName("int 범위를 넘는 메시지 번호도 커서를 오간다.")
  @Test
  void encodeAndDecode_keepsLongRange() {
    MessageCursor cursor = new MessageCursor(9_000_000_000L);

    assertThat(MessageCursor.decode(cursor.encode())).isEqualTo(cursor);
  }

  @DisplayName("판독할 수 없는 커서는 예외다.")
  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"", "   ", "!!not-base64!!", "bm90LWEtbnVtYmVy"})
  void decode_rejectsMalformed(String malformed) {
    assertThatThrownBy(() -> MessageCursor.decode(malformed))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @DisplayName("null 커서는 예외다.")
  @Test
  void decode_rejectsNull() {
    assertThatThrownBy(() -> MessageCursor.decode(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
