package com.duckmoim.auth.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.exception.AuthErrorCode;
import com.duckmoim.common.exception.BusinessException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtProviderTest {

  private static final String SECRET = "duckmoim-unit-test-secret-key-32-bytes-or-longer";
  private static final String OTHER_SECRET = "someone-elses-secret-key-32-bytes-or-longer-here";
  private static final AuthUser AUTH_USER = new AuthUser(7L, true, false);

  private static final Duration ACCESS_TTL = Duration.ofMinutes(30);
  private static final Duration REFRESH_TTL = Duration.ofDays(14);
  private static final Duration ALREADY_EXPIRED = Duration.ofMinutes(-1);

  private final JwtProvider jwtProvider = new JwtProvider(SECRET, ACCESS_TTL, REFRESH_TTL);

  @Test
  @DisplayName("발급한 토큰을 읽으면 발급할 때의 인증 주체가 그대로 나온다.")
  void createAndReadAccessToken() {
    String accessToken = jwtProvider.createAccessToken(AUTH_USER);

    assertThat(jwtProvider.readAccessToken(accessToken).authUser()).isEqualTo(AUTH_USER);
  }

  @Test
  @DisplayName("만료된 토큰은 재발급이 필요하다는 뜻으로 거절한다.")
  void readAccessToken_expired() {
    JwtProvider alreadyExpired = new JwtProvider(SECRET, ALREADY_EXPIRED, REFRESH_TTL);
    String accessToken = alreadyExpired.createAccessToken(AUTH_USER);

    assertThatThrownBy(() -> alreadyExpired.readAccessToken(accessToken))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_ACCESS_TOKEN_EXPIRED);
  }

  @Test
  @DisplayName("다른 열쇠로 서명된 토큰은 다시 로그인하라는 뜻으로 거절한다.")
  void readAccessToken_signedByAnotherKey() {
    JwtProvider forger = new JwtProvider(OTHER_SECRET, ACCESS_TTL, REFRESH_TTL);
    String forgedToken = forger.createAccessToken(AUTH_USER);

    assertThatThrownBy(() -> jwtProvider.readAccessToken(forgedToken))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID);
  }

  @Test
  @DisplayName("발급한 토큰에는 용도가 액세스로 적힌다.")
  void createAccessToken_stampsTokenType() {
    String accessToken = jwtProvider.createAccessToken(AUTH_USER);

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

  @Test
  @DisplayName("발급한 리프레시 토큰을 읽으면 발급할 때의 회원번호가 나온다.")
  void createAndReadRefreshToken() {
    String refreshToken = jwtProvider.createRefreshToken(7L);

    assertThat(jwtProvider.readRefreshToken(refreshToken).userId()).isEqualTo(7L);
  }

  @Test
  @DisplayName("발급한 리프레시 토큰에는 용도가 리프레시로 적힌다.")
  void createRefreshToken_stampsTokenType() {
    String refreshToken = jwtProvider.createRefreshToken(7L);

    assertThat(payloadOf(refreshToken)).contains("\"tokenType\":\"refresh\"");
  }

  /**
   * 가입 여부와 관리자 여부는 14일 안에 바뀐다 — 가입을 마치거나(AU-05) 관리자가 되는 일이 그 사이에 일어난다. 토큰에 박아 두면 재발급이 <b>낡은 값을 그대로
   * 새 액세스 토큰에 옮겨</b> 사용자가 2주 동안 가입 미완료로 막힌다. 그래서 재발급 때 DB 에서 다시 읽는다.
   */
  @Test
  @DisplayName("리프레시 토큰에는 가입 여부와 관리자 여부를 담지 않는다.")
  void createRefreshToken_carriesNothingButUserId() {
    String refreshToken = jwtProvider.createRefreshToken(7L);

    assertThat(payloadOf(refreshToken)).doesNotContain("signupCompleted", "admin");
  }

  /** {@code iat}·{@code exp} 가 초 단위라 임의값이 없으면 1초 안의 두 발급이 같은 토큰이 된다. */
  @Test
  @DisplayName("같은 회원에게 연달아 발급한 리프레시 토큰은 서로 다르다.")
  void createRefreshToken_isUniquePerCall() {
    assertThat(jwtProvider.createRefreshToken(7L)).isNotEqualTo(jwtProvider.createRefreshToken(7L));
  }

  @Test
  @DisplayName("액세스 토큰을 읽으면 발급 시각이 함께 나온다.")
  void readAccessToken_carriesIssuedAt() {
    String accessToken = jwtProvider.createAccessToken(AUTH_USER);

    assertThat(jwtProvider.readAccessToken(accessToken).issuedAt())
        .isCloseTo(LocalDateTime.now(ZoneOffset.UTC), within(5, ChronoUnit.SECONDS));
  }

  /** 재사용 탐지의 멱등 판정이 이 값으로 돈다 — 없으면 폐기가 매번 다시 찍혀 영구 잠금이 된다. */
  @Test
  @DisplayName("리프레시 토큰을 읽으면 발급 시각이 함께 나온다.")
  void readRefreshToken_carriesIssuedAt() {
    String refreshToken = jwtProvider.createRefreshToken(7L);

    assertThat(jwtProvider.readRefreshToken(refreshToken).issuedAt())
        .isCloseTo(LocalDateTime.now(ZoneOffset.UTC), within(5, ChronoUnit.SECONDS));
  }

  /** 반대 방향의 교차 사용이다. 액세스 토큰이 리프레시로 통하면 30분마다 갱신되는 열쇠가 재발급 창구를 연다. */
  @Test
  @DisplayName("액세스 토큰을 리프레시 토큰으로 읽으면 다시 로그인하라는 뜻으로 거절한다.")
  void readRefreshToken_tokenTypeIsAccess() {
    String accessToken = jwtProvider.createAccessToken(AUTH_USER);

    assertThatThrownBy(() -> jwtProvider.readRefreshToken(accessToken))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
  }

  /** 액세스와 달리 만료를 따로 알리지 않는다. 14일이 지난 리프레시로 할 수 있는 일이 「다시 로그인」 하나뿐이다. */
  @Test
  @DisplayName("만료된 리프레시 토큰은 다시 로그인하라는 뜻으로 거절한다.")
  void readRefreshToken_expired() {
    JwtProvider alreadyExpired = new JwtProvider(SECRET, ACCESS_TTL, ALREADY_EXPIRED);
    String refreshToken = alreadyExpired.createRefreshToken(7L);

    assertThatThrownBy(() -> alreadyExpired.readRefreshToken(refreshToken))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
  }

  @Test
  @DisplayName("다른 열쇠로 서명된 리프레시 토큰은 다시 로그인하라는 뜻으로 거절한다.")
  void readRefreshToken_signedByAnotherKey() {
    JwtProvider forger = new JwtProvider(OTHER_SECRET, ACCESS_TTL, REFRESH_TTL);
    String forgedToken = forger.createRefreshToken(7L);

    assertThatThrownBy(() -> jwtProvider.readRefreshToken(forgedToken))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
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
