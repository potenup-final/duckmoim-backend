package com.duckmoim.identity.domain;

import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.identity.infra.UserRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 토큰 무효화와 가입 완료 판정 (AU-03 · AU-04).
 *
 * <p><b>단위 테스트가 아니다.</b> {@code User} 에 생성 팩터리가 없어서 (실제 생성 경로가 AU-01 자동 가입이고 아직 없다) 행을 SQL 로 넣고 읽어야
 * 한다 — {@link com.duckmoim.identity.UserFixture} 에 이유가 있다.
 */
@SpringBootTest
@Transactional
class UserTest {

  private static final LocalDateTime LOGOUT = LocalDateTime.of(2026, 9, 7, 12, 0, 0);

  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("무효화 시각이 없으면 어느 시각에 발급된 토큰도 살아 있다.")
  void isTokenInvalidated_neverInvalidated() {
    User user = load(aUser().insert(jdbcTemplate));

    assertThat(user.isTokenInvalidated(LOGOUT.minusYears(1))).isFalse();
  }

  @Test
  @DisplayName("무효화 시각보다 먼저 발급된 토큰은 죽는다.")
  void isTokenInvalidated_authTokenBefore() {
    User user = load(aUser().tokensInvalidatedAt(LOGOUT).insert(jdbcTemplate));

    assertThat(user.isTokenInvalidated(LOGOUT.minusSeconds(1))).isTrue();
  }

  /** 로그아웃 뒤에 다시 로그인해 받은 토큰이다. 이것까지 죽이면 로그인이 아예 안 된다. */
  @Test
  @DisplayName("무효화 시각보다 나중에 발급된 토큰은 살아 있다.")
  void isTokenInvalidated_authTokenAfter() {
    User user = load(aUser().tokensInvalidatedAt(LOGOUT).insert(jdbcTemplate));

    assertThat(user.isTokenInvalidated(LOGOUT.plusSeconds(1))).isFalse();
  }

  /**
   * JWT 의 {@code iat} 은 초 단위로 내려간다. 12:00:00.400 에 발급하고 12:00:00.700 에 로그아웃하면 발급 시각이 12:00:00.000
   * 으로 들어와서, 경계가 열려 있으면 그 토큰이 30분을 더 산다.
   */
  @Test
  @DisplayName("로그아웃과 같은 초에 발급된 토큰도 죽는다.")
  void isTokenInvalidated_authTokenInTheSameSecond() {
    User user =
        load(aUser().tokensInvalidatedAt(LOGOUT.withNano(700_000_000)).insert(jdbcTemplate));

    assertThat(user.isTokenInvalidated(LOGOUT)).isTrue();
  }

  @Test
  @DisplayName("토큰을 무효화하면 그 시각이 남는다.")
  void invalidateAllTokens() {
    User user = load(aUser().insert(jdbcTemplate));

    user.invalidateAllTokens(LOGOUT);

    assertThat(user.isTokenInvalidated(LOGOUT.minusSeconds(1))).isTrue();
  }

  @Test
  @DisplayName("마지막 접속 시각을 갱신하면 그 시각이 남는다.")
  void updateLastSeenAt() {
    User user = load(aUser().lastSeenAt(LOGOUT.minusDays(30)).insert(jdbcTemplate));

    user.updateLastSeenAt(LOGOUT);

    assertThat(user.getLastSeenAt()).isEqualTo(LOGOUT);
  }

  @ParameterizedTest
  @CsvSource({"ACTIVE, true", "PENDING_SIGNUP_INFO, false", "WITHDRAWN, false"})
  @DisplayName("가입 완료는 활성 상태에서만 참이다.")
  void isSignupCompleted(SignupStatus status, boolean expected) {
    User user = load(aUser().status(status).insert(jdbcTemplate));

    assertThat(user.isSignupCompleted()).isEqualTo(expected);
  }

  private User load(long userId) {
    return userRepository.findById(userId).orElseThrow();
  }
}
