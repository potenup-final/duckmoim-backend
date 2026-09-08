package com.duckmoim.companion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 유저가 쓴 모집글 목록의 커서 (AU-09 · AU-10).
 *
 * <p>{@code PostCursor} · {@code MyCommentCursor} 와 같은 계약을 지킨다 — 불투명하고, 정확하고, 판독 실패를 예외로 올린다. 상위가
 * 그것을 INVALID_INPUT 400 으로 옮긴다 (API-컨벤션.md 「Validation 규칙」).
 */
class UserPostCursorTest {

  private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 8, 11, 20);

  @DisplayName("인코딩한 커서를 다시 읽으면 같은 값이다.")
  @Test
  void encodeAndDecode() {
    UserPostCursor cursor = new UserPostCursor(CREATED_AT, 7L);

    assertThat(UserPostCursor.decode(cursor.encode())).isEqualTo(cursor);
  }

  @DisplayName("커서에 정렬 키가 그대로 드러나지 않는다.")
  @Test
  void encode_isOpaque() {
    String encoded = new UserPostCursor(CREATED_AT, 7L).encode();

    assertThat(encoded).doesNotContain("2026", "|");
  }

  @DisplayName("URL 에 그대로 실을 수 있다.")
  @Test
  void encode_isUrlSafe() {
    String encoded = new UserPostCursor(CREATED_AT, 7L).encode();

    assertThat(encoded).doesNotContain("+", "/", "=");
  }

  @DisplayName("판독할 수 없는 커서는 거절한다.")
  @ParameterizedTest
  @ValueSource(strings = {"!!!", "MjAyNi0wOS0wOA", "MjAyNi0wOS0wOFQxMToyMHw", "fDc"})
  void decode_malformed(String encoded) {
    assertThatThrownBy(() -> UserPostCursor.decode(encoded))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @DisplayName("null 커서도 거절한다.")
  @Test
  void decode_null() {
    assertThatThrownBy(() -> UserPostCursor.decode(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @DisplayName("정렬 키가 하나라도 없으면 커서가 아니다.")
  @Test
  void requiresBothKeys() {
    assertThatThrownBy(() -> new UserPostCursor(CREATED_AT, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new UserPostCursor(null, 7L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * <b>모집글 목록 커서와 문자열이 섞이면 안 되는데, 형태만으로는 갈라지지 않는다.</b>
   *
   * <p>둘 다 {@code (시각, id)} 두 값이라 서로의 문자열이 <b>디코딩까지 성공한다</b> — API 설계 3장이 <i>"판독은 되고 페이지 경계만 조용히
   * 어긋난다"</i> 로 적어 둔 것이 이것이다. 그래서 방어는 타입을 갈라 두는 것뿐이고, 이 테스트는 그 사실을 <b>기록</b>한다. 언젠가 커서에 종류 표시가 붙으면
   * 이 단언이 뒤집히고, 그때 이 주석이 근거가 된다.
   */
  @DisplayName("모집글 목록의 커서 문자열도 판독은 된다 — 그래서 타입을 갈라 둔다.")
  @Test
  void decode_acceptsPostCursorShape() {
    String otherList = new PostCursor(CREATED_AT, 7L).encode();

    assertThat(UserPostCursor.decode(otherList)).isEqualTo(new UserPostCursor(CREATED_AT, 7L));
  }
}
