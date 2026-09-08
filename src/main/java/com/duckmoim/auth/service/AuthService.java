package com.duckmoim.auth.service;

import com.duckmoim.admin.infra.AdminAccountRepository;
import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.RefreshToken;
import com.duckmoim.auth.domain.RefreshTokenClaims;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.exception.AuthErrorCode;
import com.duckmoim.auth.infra.RefreshTokenRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.common.exception.ErrorCode;
import com.duckmoim.identity.domain.User;
import com.duckmoim.identity.exception.UserErrorCode;
import com.duckmoim.identity.infra.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
  public AuthToken createTokens(Long userId) {
    return createTokens(loadUser(userId, UserErrorCode.USER_NOT_FOUND), now());
  }

  /**
   * Refresh 토큰을 회전해 새 토큰 쌍을 준다 (AU-03).
   *
   * <p><b>회전과 재사용 판정을 {@code DELETE} 한 번이 가른다.</b> 「읽어서 있으면 지운다」로 짜면 같은 토큰으로 동시에 두 요청이 들어올 때 둘 다 존재
   * 검사를 지나고 진 쪽이 {@code StaleStateException} → 500 이 된다 — 탭 둘이 동시에 재발급하는 흔한 상황이다. 영향 행 수가 1인 요청만
   * 회전을 진행하고 0을 받은 쪽은 재사용으로 판정한다.
   *
   * <p><b>{@code noRollbackFor} 가 없으면 반대로 돈다.</b> 재사용을 탐지하면 「해당 유저 전체 폐기」를 하고 401 을 던지는데, 기본 설정이면
   * {@code RuntimeException} 에 트랜잭션이 되돌아가 <b>폐기가 취소된다.</b> 응답은 양쪽 다 401 이라 겉으로 구분되지 않는데, 실제로는 훔친 쪽만
   * 살아남는다.
   */
  @Transactional(noRollbackFor = BusinessException.class)
  public AuthToken refresh(String rawRefreshToken) {
    LocalDateTime now = now();
    RefreshTokenClaims claims = tokenProvider.readRefreshToken(rawRefreshToken);
    Long userId = claims.userId();

    int rotated =
        refreshTokenRepository.deleteByTokenHashAndUserId(
            RefreshToken.hash(rawRefreshToken), userId);

    if (rotated == 0) {
      invalidateAllTokensOnce(userId, claims.issuedAt(), now);
      throw new BusinessException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    }

    User user = loadUser(userId, AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
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
  private AuthToken createTokens(User user, LocalDateTime now) {
    String rawRefreshToken = tokenProvider.createRefreshToken(user.getId());

    refreshTokenRepository.save(
        RefreshToken.create(user.getId(), rawRefreshToken, now.plus(refreshTokenTtl)));

    AuthUser authUser = new AuthUser(user.getId(), user.isSignupCompleted(), isAdmin(user));

    return new AuthToken(
        tokenProvider.createAccessToken(authUser), rawRefreshToken, authUser.signupCompleted());
  }

  /** 그 회원의 로그인 상태를 통째로 끊는다 — AU-03 의 「해당 유저 전체 폐기」와 AU-04 로그아웃이 같은 동작이다. */
  private void invalidateAllTokens(Long userId, LocalDateTime now) {
    refreshTokenRepository.deleteAllByUserId(userId);

    userRepository.findById(userId).ifPresent(user -> user.invalidateAllTokens(now));
  }

  /**
   * <b>이미 폐기에 포함된 토큰이면 다시 폐기하지 않는다.</b>
   *
   * <p>죽은 Refresh 를 쥔 쪽이 이 공개 엔드포인트를 1초에 한 번씩 때리면, 무조건 다시 찍는 구현에서는 무효화 시각이 계속 앞으로 밀린다. 그러면 그 사이에 정상
   * 재로그인한 사용자의 새 Access 도 {@code iat} 이 초 단위라 매번 그보다 이르게 되어 <b>복구 경로 없는 영구 잠금</b>이 된다.
   *
   * <p>판정은 <b>들고 온 Refresh 의 발급 시각</b>으로 한다 ({@code User.isCoveredByPastInvalidation}). 지난 폐기보다 이른
   * 초에 발급된 토큰이면 그 폐기가 이미 이 토큰을 덮었다는 뜻이라 한 번 더 끊을 것이 없다. 반대로 폐기와 같은 초이거나 그 뒤에 발급된 토큰은 <b>폐기를 살아남은
   * 것</b>일 수 있어, 그 재사용은 새로운 탈취로 보고 정상적으로 탐지한다.
   */
  private void invalidateAllTokensOnce(
      Long userId, LocalDateTime refreshIssuedAt, LocalDateTime now) {
    User user = userRepository.findById(userId).orElse(null);

    if (user == null || user.isCoveredByPastInvalidation(refreshIssuedAt)) {
      return;
    }

    refreshTokenRepository.deleteAllByUserId(userId);
    user.invalidateAllTokens(now);
  }

  /** 관리자 판정은 화이트리스트 등록 여부다 (0003-관리자-인가-방식.md 「선택」). {@code User} 에는 관리자 플래그가 없다. */
  private boolean isAdmin(User user) {
    return adminAccountRepository.existsByKakaoUserId(user.getKakaoUserId());
  }

  /**
   * 회원을 읽는다. <b>없을 때의 에러 코드를 부르는 쪽이 정한다.</b>
   *
   * <p>재발급 경로에서는 「다시 로그인」({@code AUTH_REFRESH_TOKEN_INVALID}) 이지만, 로그인 경로에는 Refresh 토큰이 애초에 없어서 그
   * 코드가 거짓이 된다. 카카오 로그인(E)이 {@link #createTokens} 를 부르는 자리가 그렇다.
   */
  private User loadUser(Long userId, ErrorCode notFound) {
    return userRepository.findById(userId).orElseThrow(() -> new BusinessException(notFound));
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
