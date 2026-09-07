package com.duckmoim.auth.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.exception.AuthErrorCode;
import com.duckmoim.common.exception.BusinessException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
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
  @DisplayName("발급한 토큰에는 용도가 액세스로 적힌다.")
  void issueAccessToken_stampsTokenType() {
    String accessToken = jwtProvider.issueAccessToken(AUTH_USER);

    assertThat(payloadOf(accessToken)).contains("\"tokenType\":\"access\"");
  }

  /**
   * Refresh 토큰도 같은 열쇠로 서명되고 수명이 14일이라, 용도를 안 보면 <b>탈취된 Refresh 가 2주 동안 API 전체를 여는 열쇠</b>가 된다.
   * Access 를 30분으로 짧게 잡은 이유가 무력화된다.
   */
  @Test
  @DisplayName("용도가 액세스가 아닌 토큰은 다시 로그인하라는 뜻으로 거절한다.")
  void readAccessToken_tokenTypeIsNotAccess() {
    String refreshToken = tokenSignedWithSameKey("refresh");

    assertThatThrownBy(() -> jwtProvider.readAccessToken(refreshToken))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID);
  }

  @Test
  @DisplayName("용도가 적히지 않은 토큰은 다시 로그인하라는 뜻으로 거절한다.")
  void readAccessToken_tokenTypeIsMissing() {
    String legacyToken = tokenSignedWithSameKey(null);

    assertThatThrownBy(() -> jwtProvider.readAccessToken(legacyToken))
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

  /** 우리 열쇠로 서명된 진짜 토큰을 용도만 바꿔 만든다. 서명·만료로는 걸러지지 않는 것을 검증하려는 것이다. */
  private String tokenSignedWithSameKey(String tokenType) {
    SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    var builder =
        Jwts.builder().subject("7").expiration(Date.from(Instant.now().plus(Duration.ofDays(14))));

    if (tokenType != null) {
      builder.claim("tokenType", tokenType);
    }
    return builder.signWith(key).compact();
  }

  private String payloadOf(String token) {
    return new String(
        java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
  }
}
