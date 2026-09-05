package com.duckmoim.auth.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuthUserTest {

  @Test
  @DisplayName("회원번호 없이는 인증된 사용자를 만들 수 없다.")
  void create_userIdIsNull() {
    assertThatThrownBy(() -> new AuthUser(null, true, false))
        .isInstanceOf(NullPointerException.class);
  }
}
