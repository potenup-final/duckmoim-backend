package com.duckmoim.auth.service;

import com.duckmoim.admin.infra.AdminAccountRepository;
import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.RefreshToken;
import com.duckmoim.auth.domain.TokenPair;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.exception.AuthErrorCode;
import com.duckmoim.auth.infra.RefreshTokenRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.domain.User;
import com.duckmoim.identity.infra.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 토큰 발급 · 재발급 · 로그아웃 (AU-02 · AU-03 · AU-04).
 *
 * <p><b>로그인은 여기 없다.</b> 우리 로그인은 카카오 하나뿐이고(AU-01) 그 티켓이 인가코드를 회원번호로 바꿔 <b>자동 가입까지</b> 한 뒤 {@link
 * #createTokens} 를 부른다. 가입 정보 입력(AU-05)도 없다 — 닉네임과 출생연도는 {@code identity} 의 관심사고 토큰과 무관하다.
 *
 * <p><b>다른 컨텍스트 둘을 읽는다.</b> {@code identity} 의 {@link UserRepository} 와 {@code admin} 의 {@link
 * AdminAccountRepository} 다. 아키텍처-컨벤션.md 「절차」가 <i>"다른 도메인을 참조해야 한다면 먼저 의존 방향과 공개 API를 PR에서
 * 합의한다"</i> 고 정했으므로 PR 에 근거를 적는다. 방향은 {@code auth → identity} · {@code auth → admin} 한쪽이고 역참조는 없다.
 */
@Service
public class AuthService {

  private final TokenProvider tokenProvider;
  private final RefreshTokenRepository refreshTokenRepository;
  private final UserRepository userRepository;
  private final AdminAccountRepository adminAccountRepository;
  private final Duration refreshTokenTtl;

  /**
   * {@code refresh-token-ttl} 을 {@code JwtProvider} 와 <b>같은 프로퍼티</b>에서 읽는다. 저쪽은 JWT 의 {@code exp}
   * 를, 이쪽은 행의 {@code expires_at} 을 찍으므로 둘이 어긋나면 안 된다.
   */
  public AuthService(
      TokenProvider tokenProvider,
      RefreshTokenRepository refreshTokenRepository,
      UserRepository userRepository,
      AdminAccountRepository adminAccountRepository,
      @Value("${duckmoim.jwt.refresh-token-ttl}") Duration refreshTokenTtl) {
    this.tokenProvider = tokenProvider;
    this.refreshTokenRepository = refreshTokenRepository;
    this.userRepository = userRepository;
    this.adminAccountRepository = adminAccountRepository;
    this.refreshTokenTtl = refreshTokenTtl;
  }

  /**
   * 토큰 쌍을 발급하고 Refresh 를 서버에 남긴다 (AU-02).
   *
   * <p>진입점이 둘이다 — 카카오 로그인(AU-01)과 아래 {@link #refresh}. 로그인 쪽은 E 티켓이 붙인다.
   *
   * <p>이름을 {@code create} 로 맞췄다. 목적어가 있어야 호출부에서 무엇이 만들어지는지 보이고, {@code
   * TokenProvider.createAccessToken} · {@code RefreshToken.create} 와 접두어가 같아진다.
   */
  @Transactional
  public TokenPair createTokens(Long userId) {
    return createTokens(loadUser(userId), now());
  }

  /**
   * Refresh 토큰을 회전해 새 토큰 쌍을 준다 (AU-03).
   *
   * <p><b>{@code noRollbackFor} 가 이 메서드의 핵심이다.</b> 재사용을 탐지하면 「해당 유저 전체 폐기」를 하고 401 을 던지는데, 기본 설정이면
   * {@code RuntimeException} 에 트랜잭션이 되돌아가 <b>폐기가 취소된다.</b> 그러면 훔친 Refresh 를 쥔 쪽이 계속 재발급을 받는다 —
   * 요구사항이 정확히 막으라고 한 것이 되돌아간다.
   *
   * <p>행이 없다는 것이 곧 재사용이다. 서명은 멀쩡하니 진짜 우리가 발급한 토큰인데, 회전 때 지워졌으므로 누군가 이미 썼다는 뜻이다.
   */
  @Transactional(noRollbackFor = BusinessException.class)
  public TokenPair refresh(String rawRefreshToken) {
    LocalDateTime now = now();
    Long userId = tokenProvider.readRefreshToken(rawRefreshToken);

    Optional<RefreshToken> stored =
        refreshTokenRepository.findByTokenHash(RefreshToken.hash(rawRefreshToken));

    if (stored.isEmpty()) {
      invalidateAllTokens(userId, now);
      throw new BusinessException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    }

    RefreshToken rotated = stored.get();
    if (!rotated.belongsTo(userId) || rotated.isExpired(now)) {
      throw new BusinessException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    }

    refreshTokenRepository.delete(rotated);

    User user = loadUser(userId);
    user.updateLastSeenAt(now);

    return createTokens(user, now);
  }

  /**
   * 로그아웃한다 (AU-04).
   *
   * <p>행만 지우면 이미 발급된 Access 가 최대 30분 더 산다. {@code tokensInvalidatedAt} 갱신이 그 잔여 TTL 을 끊는다 — 검증 기준이
   * 「로그아웃 직후 기존 Access 로 401」이다.
   */
  @Transactional
  public void logout(Long userId) {
    invalidateAllTokens(userId, now());
  }

  /**
   * Access 에 찍는 {@code signupCompleted} 와 {@code admin} 을 <b>매번 다시 읽는다.</b> Refresh 는 14일을 사는데 그
   * 사이에 가입이 완료되거나(AU-05) 관리자 등록이 바뀔 수 있어서, 토큰에 물려주면 낡은 값이 2주 동안 따라다닌다.
   */
  private TokenPair createTokens(User user, LocalDateTime now) {
    String rawRefreshToken = tokenProvider.createRefreshToken(user.getId());

    refreshTokenRepository.save(
        RefreshToken.create(user.getId(), rawRefreshToken, now.plus(refreshTokenTtl)));

    AuthUser authUser = new AuthUser(user.getId(), user.isSignupCompleted(), isAdmin(user));

    return new TokenPair(tokenProvider.createAccessToken(authUser), rawRefreshToken);
  }

  /** 그 회원의 로그인 상태를 통째로 끊는다 — AU-03 의 「해당 유저 전체 폐기」와 AU-04 로그아웃이 같은 동작이다. */
  private void invalidateAllTokens(Long userId, LocalDateTime now) {
    refreshTokenRepository.deleteAllByUserId(userId);

    userRepository.findById(userId).ifPresent(user -> user.invalidateAllTokens(now));
  }

  /** 관리자 판정은 화이트리스트 등록 여부다 (0003-관리자-인가-방식.md 「선택」). {@code User} 에는 관리자 플래그가 없다. */
  private boolean isAdmin(User user) {
    return adminAccountRepository.existsByKakaoUserId(user.getKakaoUserId());
  }

  private User loadUser(Long userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID));
  }

  /**
   * 저장하는 시각은 UTC 다 — {@code BaseEntity} 가 {@code created_at}·{@code updated_at} 을 찍는 방식과 같다.
   *
   * <p><b>{@code Clock} 을 주입받지 않는다.</b> 주입된 빈은 {@code Asia/Seoul} 이라(행사 종료일 판정이 KST 여야 해서 그렇게 정해졌다)
   * 여기서 쓰면 UTC 컬럼과 9시간 어긋난다. 그리고 이 서비스에는 시각을 고정해야 하는 검증이 없다 — 만료 판정은 행의 {@code expires_at} 을 픽스처가
   * 정해서 넣는다. 고정이 필요해지면 그때 {@code Clock} 을 받는다.
   */
  private LocalDateTime now() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }
}
