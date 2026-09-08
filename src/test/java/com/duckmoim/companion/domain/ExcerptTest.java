package com.duckmoim.companion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 목록은 본문 대신 잘라낸 미리보기를 싣는다 (PO-08 · API-설계.md 「2-4. 모집글 (Companion)」). */
class ExcerptTest {

  @DisplayName("짧은 본문은 그대로 나온다.")
  @Test
  void of_isShort() {
    assertThat(Excerpt.of("같이 가실 분 찾아요.")).isEqualTo("같이 가실 분 찾아요.");
  }

  @DisplayName("긴 본문은 앞에서 잘라 싣는다.")
  @Test
  void of_isLong() {
    String content = "가".repeat(500);

    assertThat(Excerpt.of(content)).hasSize(100);
  }

  /** 카드는 한 덩이의 문장으로 그려진다. 단락 구분이 그대로 실리면 두 줄 안에서 줄바꿈만 보인다. */
  @DisplayName("본문의 개행은 공백으로 접는다.")
  @Test
  void of_foldsNewLines() {
    assertThat(Excerpt.of("오픈런 같이 하실 분.\n\n9시에 만나요.")).isEqualTo("오픈런 같이 하실 분. 9시에 만나요.");
  }

  /** 빈 문자열로 바꾸면 「본문 없는 글」과 「본문이 빈 글」이 화면에서 구분되지 않는다. */
  @DisplayName("본문이 없으면 미리보기도 없다.")
  @Test
  void of_hasNoContent() {
    assertThat(Excerpt.of(null)).isNull();
  }

  @DisplayName("잘렸다는 표시를 서버가 붙이지 않는다.")
  @Test
  void of_hasNoEllipsis() {
    assertThat(Excerpt.of("가".repeat(500))).doesNotContain("…", "...");
  }
}
