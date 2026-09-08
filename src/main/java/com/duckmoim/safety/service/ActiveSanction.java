package com.duckmoim.safety.service;

import com.duckmoim.safety.domain.SanctionKind;
import java.time.LocalDateTime;

/**
 * 지금 유효한 제재 한 건 (AD-04 · AU-12).
 *
 * <p>엔티티를 service 의 public 시그니처에 노출하지 않는다는 규칙(아키텍처-컨벤션.md 「service」)에 따라 둔 결과 객체다. 값 객체와 enum 은
 * presentation 이 참조해도 된다 (같은 문서 「의존성 방향」 3번).
 *
 * @param reason 본인에게 그대로 보여준다 (AD-04 · AU-12). 제재 사유는 노출해도 되는 정보다
 * @param until {@code SUSPENDED} 일 때만 값이 있다 (화면 계약 「제재 상태」)
 * @param issuedAt 발효 시각. 저장된 값 그대로 UTC 다 — KST 로 옮기는 것은 presentation 이 한다
 */
public record ActiveSanction(
    SanctionKind kind, String reason, LocalDateTime until, LocalDateTime issuedAt) {}
