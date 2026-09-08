package com.duckmoim.auth.service;

import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.RefreshToken;
import com.duckmoim.auth.domain.TokenPair;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.exception.AuthErrorCode;
import com.duckmoim.auth.infra.RefreshTokenRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.domain.SignupStatus;
import com.duckmoim.identity.infra.UserRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 재발급과 로그아웃 (AU-03 · AU-04).
 *
 * <p><b>{@code @Transactional} 을 쓰지 않는다.</b> 재사용 탐지가 「401 을 던지면서 폐기는 커밋한다」로 동작하는데(그래서 서비스가 {@code
 * noRollbackFor} 를 건다), 테스트에 롤백을 걸면 그 커밋이 사라져서 <b>무엇을 검증해도 통과한다.</b> 테스트 컨벤션 「테스트 데이터 정리」가 트랜잭션이
 * 개입하는 검증에 롤백을 쓰지 말라고 정한 이유가 이것이다. 대신 넣은 회원을 뒤에서 직접 지운다.
 */
@SpringBootTest
class AuthServiceTest {

  @Autowired private AuthService authService;
  @Autowired private TokenProvider tokenProvider;
  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("리프레시 토큰으로 재발급하면 새 토큰 쌍이 나온다.")
  void refresh() {
    long userId = aUser().insert(jdbcTemplate);
    TokenPair issued = firstLogin(userId);

    TokenPair refreshd = authService.refresh(issued.refreshToken());

    assertThat(refreshd.accessToken()).isNotBlank();
    assertThat(refreshd.refreshToken()).isNotEqualTo(issued.refreshToken());
    cleanUp(userId);
  }

  /** 회전이다 — 새 것을 주면서 헌 것을 지운다. 이것이 재사용 탐지의 전부다. */
  @Test
  @DisplayName("재발급하면 쓰인 리프레시 토큰은 저장소에서 사라진다.")
  void refresh_removesUsedToken() {
    long userId = aUser().insert(jdbcTemplate);
    TokenPair issued = firstLogin(userId);

    authService.refresh(issued.refreshToken());

    assertThat(refreshTokenRepository.findByTokenHash(RefreshToken.hash(issued.refreshToken())))
        .isEmpty();
    cleanUp(userId);
  }

  /** AU-03 의 검증 기준 앞쪽 — 「동일 Refresh 2회 사용 시 두 번째 거부」. */
  @Test
  @DisplayName("같은 리프레시 토큰을 두 번 쓰면 두 번째는 거부된다.")
  void refresh_reused() {
    long userId = aUser().insert(jdbcTemplate);
    TokenPair issued = firstLogin(userId);
    authService.refresh(issued.refreshToken());

    assertThatThrownBy(() -> authService.refresh(issued.refreshToken()))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    cleanUp(userId);
  }

  /**
   * AU-03 의 검증 기준 뒤쪽 — 「기존 세션 무효화」.
   *
   * <p>재사용을 탐지한 시점에 정상 사용자는 <b>이미 회전된 새 토큰</b>을 쥐고 있다. 그것까지 끊어야 훔친 쪽과 함께 로그아웃된다. 이 단언이 {@code
   * noRollbackFor} 를 지킨다 — 빼면 폐기가 롤백돼도 초록불이다.
   */
  @Test
  @DisplayName("재사용을 탐지하면 회전으로 받은 새 토큰까지 폐기된다.")
  void refresh_reusedInvalidatesEverySession() {
    long userId = aUser().insert(jdbcTemplate);
    TokenPair issued = firstLogin(userId);
    TokenPair rotated = authService.refresh(issued.refreshToken());

    assertThatThrownBy(() -> authService.refresh(issued.refreshToken()))
        .isInstanceOf(BusinessException.class);

    assertThatThrownBy(() -> authService.refresh(rotated.refreshToken()))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    cleanUp(userId);
  }

  @Test
  @DisplayName("재사용을 탐지하면 이미 발급된 액세스 토큰도 무효화된다.")
  void refresh_reusedInvalidatesIssuedAccessTokens() {
    long userId = aUser().insert(jdbcTemplate);
    TokenPair issued = firstLogin(userId);
    authService.refresh(issued.refreshToken());

    assertThatThrownBy(() -> authService.refresh(issued.refreshToken()))
        .isInstanceOf(BusinessException.class);

    assertThat(invalidatedAt(userId)).isNotNull();
    cleanUp(userId);
  }

  /** 남의 것까지 끊으면 재사용 탐지 한 건이 전체 로그아웃이 된다. */
  @Test
  @DisplayName("재사용을 탐지해도 다른 회원의 세션은 남는다.")
  void refresh_reusedKeepsOtherUsers() {
    long userId = aUser().insert(jdbcTemplate);
    long otherUserId = aUser().insert(jdbcTemplate);
    TokenPair issued = firstLogin(userId);
    TokenPair othersToken = firstLogin(otherUserId);
    authService.refresh(issued.refreshToken());

    assertThatThrownBy(() -> authService.refresh(issued.refreshToken()))
        .isInstanceOf(BusinessException.class);

    assertThat(authService.refresh(othersToken.refreshToken()).accessToken()).isNotBlank();
    cleanUp(userId);
    cleanUp(otherUserId);
  }

  @Test
  @DisplayName("액세스 토큰으로 재발급하면 거부된다.")
  void refresh_accessToken() {
    long userId = aUser().insert(jdbcTemplate);
    TokenPair issued = firstLogin(userId);

    assertThatThrownBy(() -> authService.refresh(issued.accessToken()))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    cleanUp(userId);
  }

  /** AU-03 「재발급 성공 시 lastSeenAt 갱신」. 30분마다 재발급이 오므로 별도 핑 API 가 필요 없다. */
  @Test
  @DisplayName("재발급에 성공하면 마지막 접속 시각이 갱신된다.")
  void refresh_updatesLastSeenAt() {
    LocalDateTime longAgo = LocalDateTime.now(ZoneOffset.UTC).minusDays(30);
    long userId = aUser().lastSeenAt(longAgo).insert(jdbcTemplate);
    TokenPair issued = firstLogin(userId);

    authService.refresh(issued.refreshToken());

    assertThat(userRepository.findById(userId).orElseThrow().getLastSeenAt()).isAfter(longAgo);
    cleanUp(userId);
  }

  /** 가입을 마친 뒤 재발급하면 새 액세스 토큰이 그 사실을 담아야 한다. 리프레시에 담아 두면 14일 동안 낡은 값이 따라온다. */
  @Test
  @DisplayName("가입 미완료로 발급받았어도 가입을 마친 뒤 재발급하면 가입 완료로 나온다.")
  void refresh_readsSignupStatusAgain() {
    long userId = aUser().status(SignupStatus.PENDING_SIGNUP_INFO).insert(jdbcTemplate);
    TokenPair issued = firstLogin(userId);
    jdbcTemplate.update("UPDATE user SET status = 'ACTIVE' WHERE id = ?", userId);

    TokenPair refreshd = authService.refresh(issued.refreshToken());

    assertThat(tokenProvider.readAccessToken(refreshd.accessToken()))
        .isEqualTo(new AuthUser(userId, true, false));
    cleanUp(userId);
  }

  /** AU-04 의 검증 기준 — 「로그아웃 직후 기존 Access 로 401」의 근거다. */
  @Test
  @DisplayName("로그아웃하면 리프레시 토큰이 사라지고 액세스 토큰도 무효화된다.")
  void logout() {
    long userId = aUser().insert(jdbcTemplate);
    TokenPair issued = firstLogin(userId);

    authService.logout(userId);

    assertThat(refreshTokenRepository.findByTokenHash(RefreshToken.hash(issued.refreshToken())))
        .isEmpty();
    assertThat(invalidatedAt(userId)).isNotNull();
    cleanUp(userId);
  }

  @Test
  @DisplayName("로그아웃한 뒤에는 그 리프레시 토큰으로 재발급할 수 없다.")
  void logout_thenReissue() {
    long userId = aUser().insert(jdbcTemplate);
    TokenPair issued = firstLogin(userId);
    authService.logout(userId);

    assertThatThrownBy(() -> authService.refresh(issued.refreshToken()))
        .isInstanceOf(BusinessException.class)
        .hasFieldOrPropertyWithValue("errorCode", AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    cleanUp(userId);
  }

  /**
   * 최초 로그인이 하는 일이다.
   *
   * <p>실제 진입점은 카카오 로그인(AU-01)이고 E 티켓의 몫이지만, 발급 자체는 이 티켓의 AU-02 라 서비스의 공개 메서드를 그대로 부른다. 여기서 베껴 쓰면
   * {@code admin} 판정이나 Refresh 수명이 프로덕션과 조용히 갈라진다.
   */
  private TokenPair firstLogin(long userId) {
    return authService.createTokens(userId);
  }

  private LocalDateTime invalidatedAt(long userId) {
    return jdbcTemplate.queryForObject(
        "SELECT tokens_invalidated_at FROM user WHERE id = ?", LocalDateTime.class, userId);
  }

  /** SQL 로 지운다 — 파생 삭제는 호출한 쪽의 트랜잭션을 요구하고 이 테스트에는 그것이 없다. */
  private void cleanUp(long userId) {
    jdbcTemplate.update("DELETE FROM refresh_token WHERE user_id = ?", userId);
    jdbcTemplate.update("DELETE FROM user WHERE id = ?", userId);
  }
}
