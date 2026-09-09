package com.duckmoim.auth.domain;

import java.util.Objects;

public record AuthUser(Long userId, boolean signupCompleted, boolean admin) {

  public AuthUser {
    Objects.requireNonNull(userId, "인증된 사용자는 회원번호를 가진다.");
  }
}
