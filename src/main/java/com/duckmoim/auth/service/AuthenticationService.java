package com.duckmoim.auth.service;

import com.duckmoim.auth.domain.AccessTokenClaims;
import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.exception.AuthErrorCode;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.domain.User;
import com.duckmoim.identity.infra.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 요청 하나의 인증 주체를 판정한다 — 서명만이 아니라 <b>서버가 아직 인정하는 토큰인지</b>까지 본다.
 *
 * <p><b>왜 필터가 아니라 여기인가.</b> 판정에 회원 조회가 필요한데 {@code presentation} 은 {@code infra} 를 참조할 수 없다
 * (ArchUnit {@code LAYER_DEPENDENCY}). 필터는 이 서비스를 부르고 결과만 SecurityContext 에 넣는다.
 *
 * <p><b>왜 서명만으로 부족한가.</b> Access 토큰은 DB 에 없고 서명으로만 검증된다. 그래서 로그아웃해도(AU-04) 재사용이 탐지돼도(AU-03) 이미 나간
 * 토큰이 최대 30분을 더 산다. {@code tokens_invalidated_at} 보다 먼저 발급된 토큰을 여기서 끊는다.
 */
@Service
@RequiredArgsConstructor
public class AuthenticationService {

  private final TokenProvider tokenProvider;
  private final UserRepository userRepository;

  /**
   * 토큰을 인증 주체로 바꾼다. 판정 셋을 차례로 지난다 — 서명·용도·만료, 회원 실재, 무효화 시각.
   *
   * <p><b>무효화된 토큰에 {@code AUTH_ACCESS_TOKEN_EXPIRED} 를 쓰지 않는다.</b> 그 코드는 「재발급해 주세요」라는 뜻인데, 무효화된 회원은
   * Refresh 행도 함께 지워져 재발급이 반드시 실패한다. 「로그인이 필요합니다」로 보내야 한 번에 끝난다.
   *
   * <p><b>탈퇴한 회원도 여기서 걸린다 — 행이 없어서가 아니다.</b> 탈퇴는 {@code withdrawn_at} 을 찍는 소프트 삭제라 행이 그대로 남으므로
   * (V10) 존재 여부만 보면 <b>탈퇴 계정의 토큰이 계속 통과한다</b> — 실측했다. {@code isWithdrawn} 을 함께 본다.
   *
   * <p>위키가 {@code USER_NOT_FOUND} 를 「탈퇴 포함」으로 정의해 둔 것과 같은 취급이다 (API-설계.md 「에러 코드」) — <b>탈퇴한 회원은 없는
   * 회원으로 본다.</b> 다만 관문은 존재 여부를 알려주지 않으므로 401 로 끊는다.
   *
   * <p><b>토큰은 한 번만 파싱한다.</b> 인증 주체와 발급 시각을 따로 읽으면 그 사이에 만료가 걸려, 뒤 파싱만 {@code ExpiredJwtException} 이
   * 나면서 「재발급하라」가 「다시 로그인하라」로 바뀐다.
   *
   * <p><b>가입 완료 여부는 토큰이 아니라 회원 행에서 읽는다</b> (I-02 · AU-07). 토큰의 클레임은 발급 시점의 사진이라 최대 30분 낡는다 — 가입 정보를
   * 방금 입력한 사용자가 <b>재발급 전까지 계속 「가입 정보를 먼저 입력해 주세요」로 막혔다.</b> 회원은 위에서 이미 읽었으므로 조회가 늘지 않는다.
   */
  @Transactional(readOnly = true)
  public AuthUser authenticate(String accessToken) {
    AccessTokenClaims claims = tokenProvider.readAccessToken(accessToken);

    User user =
        userRepository
            .findById(claims.authUser().userId())
            .filter(found -> !found.isWithdrawn())
            .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID));

    if (user.isTokenInvalidated(claims.issuedAt())) {
      throw new BusinessException(AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID);
    }

    return authUserOf(user, claims.authUser());
  }

  /**
   * 관문이 쓸 인증 주체를 만든다.
   *
   * <p><b>{@code signupCompleted} 만 회원 행에서 덮는다.</b> 관리자 여부는 아직 클레임을 그대로 쓴다 — 화이트리스트 조회가 별도 표라 조회가
   * 하나 늘고, 그 자리를 관리자 경로로 좁힐지가 따로 판단할 일이다 (AD-06).
   *
   * <p>회원번호도 회원 행에서 가져온다. 클레임의 {@code sub} 로 찾은 행이라 같은 값이지만, <b>판정에 쓰는 값의 출처를 하나로</b> 두면 다음 사람이 어디를
   * 믿어야 하는지 묻지 않는다.
   */
  private AuthUser authUserOf(User user, AuthUser fromToken) {
    return new AuthUser(user.getId(), user.isSignupCompleted(), fromToken.admin());
  }
}
