package com.duckmoim.auth.infra;

import com.duckmoim.auth.domain.AccessTokenClaims;
import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.RefreshTokenClaims;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.exception.AuthErrorCode;
import com.duckmoim.common.exception.BusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtProvider implements TokenProvider {

  private static final String CLAIM_TOKEN_TYPE = "tokenType";

  private static final String ACCESS = "access";
  private static final String REFRESH = "refresh";

  private static final String CLAIM_SIGNUP_COMPLETED = "signupCompleted";
  private static final String CLAIM_ADMIN = "admin";

  private final SecretKey key;
  private final Duration accessTokenTtl;
  private final Duration refreshTokenTtl;

  public JwtProvider(
      @Value("${duckmoim.jwt.secret}") String secret,
      @Value("${duckmoim.jwt.access-token-ttl}") Duration accessTokenTtl,
      @Value("${duckmoim.jwt.refresh-token-ttl}") Duration refreshTokenTtl) {
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.accessTokenTtl = accessTokenTtl;
    this.refreshTokenTtl = refreshTokenTtl;
  }

  @Override
  public String createAccessToken(AuthUser authUser) {
    Instant now = Instant.now();

    return Jwts.builder()
        .subject(String.valueOf(authUser.userId()))
        .claim(CLAIM_TOKEN_TYPE, ACCESS)
        .claim(CLAIM_SIGNUP_COMPLETED, authUser.signupCompleted())
        .claim(CLAIM_ADMIN, authUser.admin())
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(accessTokenTtl)))
        .signWith(key)
        .compact();
  }

  /**
   * 만료를 먼저 잡는다. {@code ExpiredJwtException} 이 {@code JwtException} 의 하위라 순서가 뒤집히면 「재발급하라」가 사라진다.
   */
  @Override
  public AccessTokenClaims readAccessToken(String accessToken) {
    try {
      Claims claims =
          Jwts.parser().verifyWith(key).build().parseSignedClaims(accessToken).getPayload();

      if (!ACCESS.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
        throw new BusinessException(AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID);
      }

      AuthUser authUser =
          new AuthUser(
              Long.valueOf(claims.getSubject()),
              Boolean.TRUE.equals(claims.get(CLAIM_SIGNUP_COMPLETED, Boolean.class)),
              Boolean.TRUE.equals(claims.get(CLAIM_ADMIN, Boolean.class)));

      return new AccessTokenClaims(authUser, issuedAtOf(claims));
    } catch (ExpiredJwtException e) {
      throw new BusinessException(AuthErrorCode.AUTH_ACCESS_TOKEN_EXPIRED);
    } catch (JwtException | IllegalArgumentException | NullPointerException e) {
      throw new BusinessException(AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID);
    }
  }

  /**
   * <b>{@code jti} 가 없으면 안 된다.</b> 담기는 것이 {@code sub}·{@code tokenType}·{@code iat}·{@code exp} 뿐이고
   * 뒤의 둘은 초 단위라, 같은 회원이 1초 안에 두 번 발급받으면 토큰이 바이트까지 같아진다. 그러면 {@code token_hash} 유니크 제약에 걸려 재발급이 500
   * 이 된다 — 실측했다. 임의값 하나로 매 발급이 갈라진다.
   */
  @Override
  public String createRefreshToken(Long userId) {
    Instant now = Instant.now();

    return Jwts.builder()
        .subject(String.valueOf(userId))
        .id(UUID.randomUUID().toString())
        .claim(CLAIM_TOKEN_TYPE, REFRESH)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(refreshTokenTtl)))
        .signWith(key)
        .compact();
  }

  /**
   * 만료를 따로 구분하지 않는다. 에러 코드 표에 {@code AUTH_REFRESH_TOKEN_EXPIRED} 가 없고(API-설계.md 「에러 코드」), 만료된
   * Refresh 로 할 수 있는 일이 「다시 로그인」 하나뿐이라 위조와 같은 안내가 맞다. Access 는 「재발급하라」와 「다시 로그인하라」가 갈리므로 거기서만 구분한다.
   */
  @Override
  public RefreshTokenClaims readRefreshToken(String refreshToken) {
    try {
      Claims claims =
          Jwts.parser().verifyWith(key).build().parseSignedClaims(refreshToken).getPayload();

      if (!REFRESH.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
        throw new BusinessException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
      }

      return new RefreshTokenClaims(Long.valueOf(claims.getSubject()), issuedAtOf(claims));
    } catch (JwtException | IllegalArgumentException | NullPointerException e) {
      throw new BusinessException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    }
  }

  /** 무효화 시각과 견주려면 저장 형식과 같아야 한다 — {@code BaseEntity} 가 찍는 UTC {@code LocalDateTime} 이다. */
  private LocalDateTime issuedAtOf(Claims claims) {
    return LocalDateTime.ofInstant(claims.getIssuedAt().toInstant(), ZoneOffset.UTC);
  }
}
