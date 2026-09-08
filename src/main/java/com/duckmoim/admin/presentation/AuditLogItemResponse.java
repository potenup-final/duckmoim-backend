package com.duckmoim.admin.presentation;

import com.duckmoim.admin.domain.AuditKind;
import com.duckmoim.admin.domain.AuditTargetType;
import com.duckmoim.admin.service.AuditLogView;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 감사 로그 한 건 (화면-계약.md 「감사 로그」).
 *
 * <p>필드 이름은 계약을 그대로 따랐다 — {@code at} 은 저장 컬럼과도 같은 이름이지만, 다른 응답의 {@code createdAt} 과 달리 이 기록에는 「생성」과
 * 구분되는 「행위」 시각이 따로 없어서다.
 *
 * @param actor 행위자의 닉네임이다. 회원번호도 카카오 회원번호도 응답에 싣지 않는다
 * @param at 저장은 UTC 이고 응답은 KST 오프셋을 포함한다 (API-컨벤션.md 「필드 표기 규칙」)
 * @param detail 무엇을 왜 했는지의 한 줄. 없으면 null 이다
 */
public record AuditLogItemResponse(
    Long id,
    OffsetDateTime at,
    String actor,
    AuditKind kind,
    AuditTargetType targetType,
    Long targetId,
    String detail) {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  static AuditLogItemResponse from(AuditLogView view) {
    return new AuditLogItemResponse(
        view.id(),
        toKst(view.at()),
        view.actor(),
        view.kind(),
        view.targetType(),
        view.targetId(),
        view.detail());
  }

  private static OffsetDateTime toKst(LocalDateTime storedInUtc) {
    return storedInUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toOffsetDateTime();
  }
}
