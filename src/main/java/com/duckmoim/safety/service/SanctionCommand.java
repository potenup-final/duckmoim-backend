package com.duckmoim.safety.service;

import com.duckmoim.safety.domain.SanctionKind;
import java.time.LocalDateTime;

/**
 * 유저에게 제재를 걸라는 요청 (AD-04).
 *
 * <p>요청 DTO 를 service 로 그대로 내리지 않는다 (아키텍처-컨벤션.md 「의존성 방향」).
 *
 * @param adminUserId 제재를 건 관리자의 회원번호. <b>인가가 아니라 감사 로그의 행위자다</b> — 관리자 판정은 관문에서 이미 끝났다
 * @param until {@code SUSPENDED} 일 때만 준다. 저장은 UTC 다
 */
public record SanctionCommand(
    Long userId, SanctionKind kind, String reason, LocalDateTime until, Long adminUserId) {}
