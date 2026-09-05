package com.duckmoim.auth.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.exception.AuthErrorCode;
import com.duckmoim.common.exception.BusinessException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtProviderTest {

  private static final String SECRET = "duckmoim-unit-test-secret-key-32-bytes-or-longer";
  private static final String OTHER_SECRET = "someone-elses-secret-key-32-bytes-or-longer-here";
  private static final AuthUser AUTH_USER = new AuthUser(7L, true, false);

  private final JwtProvider jwtProvider = new JwtProvider(SECRET, Duration.ofMinutes(30));

  @Test
  @DisplayName("발급한 토큰을 읽으면 발급할 때의 인증 주체가 그대로 나온다.")
  void issueAndReadAccessToken() {
    String accessToken = jwtProvider.issueAccessToken(AUTH_USER);

    AuthUser read = jwtProvider.readAccessToken(accessToken);

    assertThat(read).isEqualTo(AUTH_USER);
  }

  @Test
  @DisplayName("만료된 토큰은 재발급이 필요하다는 뜻으로 거절한다.")
  void readAccessToken_expired() {
    JwtProvider alreadyExpired = new JwtProvider(SECRET, Duration.ofMinutes(-1));
    String accessToken = alreadyExpired.issueAccessToken(AUTH_USER);

    assertThatThrownBy(() -> alreadyExpired.readAccessToken(accessToken))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_ACCESS_TOKEN_EXPIRED);
  }

  @Test
  @DisplayName("다른 열쇠로 서명된 토큰은 다시 로그인하라는 뜻으로 거절한다.")
  void readAccessToken_signedByAnotherKey() {
    JwtProvider forger = new JwtProvider(OTHER_SECRET, Duration.ofMinutes(30));
    String forgedToken = forger.issueAccessToken(AUTH_USER);

    assertThatThrownBy(() -> jwtProvider.readAccessToken(forgedToken))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID);
  }

  @Test
  @DisplayName("토큰 형식이 아닌 문자열은 다시 로그인하라는 뜻으로 거절한다.")
  void readAccessToken_notAToken() {
    assertThatThrownBy(() -> jwtProvider.readAccessToken("이건토큰이아니다"))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID);
  }
}
