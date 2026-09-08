package com.duckmoim.auth.domain;

import java.time.LocalDateTime;

/**
 * Refresh 토큰을 한 번 읽어 얻는 것 전부 (AU-03).
 *
 * <p><b>{@code issuedAt} 이 재사용 탐지의 멱등성을 만든다.</b> 이미 폐기된 토큰을 다시 들고 오는 요청이 폐기를 <b>또</b> 실행하면, 그 사이에
 * 정상 재로그인한 사용자의 새 토큰까지 계속 끊겨 <b>영구 잠금</b>이 된다. 「이 토큰이 이미 그 폐기에 포함됐는가」를 발급 시각으로 판정한다.
 */
public record RefreshTokenClaims(Long userId, LocalDateTime issuedAt) {}
