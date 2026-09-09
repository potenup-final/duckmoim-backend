package com.duckmoim.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefreshTokenTest {

  private static final String RAW = "eyJhbGciOiJIUzM4NCJ9.refresh-token-원문";
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 7, 12, 0);

  @Test
  @DisplayName("발급하면 원문이 아니라 해시가 담긴다.")
  void issue_storesHashNotRaw() {
    RefreshToken refreshToken = RefreshToken.create(1L, RAW, NOW.plusDays(14));

    assertThat(refreshToken.getTokenHash()).isNotEqualTo(RAW).hasSize(64).isHexadecimal();
  }

  /** 조회가 해시로 이뤄지므로, 같은 원문이 같은 해시를 만들지 않으면 재발급이 전부 실패한다. */
  @Test
  @DisplayName("같은 원문은 항상 같은 해시가 된다.")
  void hash_isDeterministic() {
    String hashed = RefreshToken.hash(RAW);

    assertThat(hashed).isEqualTo(RefreshToken.create(1L, RAW, NOW.plusDays(14)).getTokenHash());
  }

  @Test
  @DisplayName("다른 원문은 다른 해시가 된다.")
  void hash_differsByRawToken() {
    String hashed = RefreshToken.hash(RAW);

    assertThat(hashed).isNotEqualTo(RefreshToken.hash(RAW + "x"));
  }
}
