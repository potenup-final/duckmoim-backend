package com.duckmoim.auth.domain;

import java.time.LocalDateTime;

/**
 * Access 토큰을 한 번 읽어 얻는 것 전부 (AU-04).
 *
 * <p><b>{@code issuedAt} 을 {@link AuthUser} 에 넣지 않는다.</b> 그것은 {@code @AuthenticationPrincipal} 로 남의
 * 컨트롤러가 받는 공개 계약이고, 발급 시각은 토큰의 사정이지 요청자의 속성이 아니다.
 *
 * <p><b>둘을 한 객체로 묶은 이유 — 파싱을 두 번 하면 그 사이에 만료가 걸린다.</b> 앞 파싱은 통과하고 뒤 파싱만 {@code ExpiredJwtException}
 * 이 나면 「재발급하라」로 안내해야 할 상황이 「다시 로그인하라」가 된다. 검증 한 번에 둘을 뽑는다.
 */
public record AccessTokenClaims(AuthUser authUser, LocalDateTime issuedAt) {}
