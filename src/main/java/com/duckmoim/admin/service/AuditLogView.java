package com.duckmoim.admin.service;

import com.duckmoim.admin.domain.AuditKind;
import com.duckmoim.admin.domain.AuditTargetType;
import java.time.LocalDateTime;

/**
 * 감사 로그 한 건의 조회 결과 (AD-05).
 *
 * <p>엔티티를 service 의 public 시그니처에 노출하지 않는다는 규칙(아키텍처-컨벤션.md 「service」)에 따라 둔 결과 객체다. 값 객체와 enum 은
 * presentation 이 참조해도 된다 (같은 문서 「의존성 방향」 3번).
 *
 * <p>{@code actor} 는 조회 시점에 조인한 닉네임이다. 기록에는 회원번호만 있다.
 *
 * @param at 저장된 값 그대로 UTC 다. KST 로 옮기는 것은 presentation 이 한다
 */
public record AuditLogView(
    Long id,
    LocalDateTime at,
    String actor,
    AuditKind kind,
    AuditTargetType targetType,
    Long targetId,
    String detail) {}
