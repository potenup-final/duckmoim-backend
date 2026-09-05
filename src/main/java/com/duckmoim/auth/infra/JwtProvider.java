package com.duckmoim.auth.infra;

import com.duckmoim.auth.domain.AuthUser;
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
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtProvider implements TokenProvider {

  private static final String CLAIM_SIGNUP_COMPLETED = "signupCompleted";
  private static final String CLAIM_ADMIN = "admin";

  private final SecretKey key;
  private final Duration accessTokenTtl;

  public JwtProvider(
      @Value("${duckmoim.jwt.secret}") String secret,
      @Value("${duckmoim.jwt.access-token-ttl}") Duration accessTokenTtl) {
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.accessTokenTtl = accessTokenTtl;
  }

  @Override
  public String issueAccessToken(AuthUser authUser) {
    Instant now = Instant.now();

    return Jwts.builder()
        .subject(String.valueOf(authUser.userId()))
        .claim(CLAIM_SIGNUP_COMPLETED, authUser.signupCompleted())
        .claim(CLAIM_ADMIN, authUser.admin())
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(accessTokenTtl)))
        .signWith(key)
        .compact();
  }

  @Override
  public AuthUser readAccessToken(String accessToken) {
    try {
      Claims claims =
          Jwts.parser().verifyWith(key).build().parseSignedClaims(accessToken).getPayload();

      return new AuthUser(
          Long.valueOf(claims.getSubject()),
          Boolean.TRUE.equals(claims.get(CLAIM_SIGNUP_COMPLETED, Boolean.class)),
          Boolean.TRUE.equals(claims.get(CLAIM_ADMIN, Boolean.class)));
    } catch (ExpiredJwtException e) {
      throw new BusinessException(AuthErrorCode.AUTH_ACCESS_TOKEN_EXPIRED);
    } catch (JwtException | IllegalArgumentException e) {
      throw new BusinessException(AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID);
    }
  }
}
