package com.duckmoim.auth.service;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.auth.exception.AuthErrorCode;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.domain.User;
import com.duckmoim.identity.infra.UserRepository;
import java.time.LocalDateTime;
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
   * <p><b>탈퇴·삭제된 회원도 여기서 걸린다.</b> 토큰은 살아 있는데 행이 없는 경우다.
   */
  @Transactional(readOnly = true)
  public AuthUser authenticate(String accessToken) {
    AuthUser authUser = tokenProvider.readAccessToken(accessToken);
    LocalDateTime issuedAt = tokenProvider.readAccessTokenIssuedAt(accessToken);

    User user =
        userRepository
            .findById(authUser.userId())
            .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID));

    if (user.isTokenInvalidated(issuedAt)) {
      throw new BusinessException(AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID);
    }

    return authUser;
  }
}
