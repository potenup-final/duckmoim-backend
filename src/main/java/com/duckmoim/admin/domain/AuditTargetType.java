package com.duckmoim.admin.domain;

/**
 * 감사 로그가 가리키는 대상의 종류 (AD-05).
 *
 * <p><b>둘이다.</b> 문서가 집합을 명시하지 않아 {@link AuditKind} 의 다섯에서 끌어냈다 — 제재 · 해제 · 파기는 유저를, 비밀 댓글 열람 ·
 * 블라인드는 댓글을 가리킨다.
 *
 * <p><b>{@code ReportTargetType} 을 돌려 쓰지 않는다.</b> 신고에는 {@code POST} 가 있는데 감사 로그가 모집글을 가리키는 행위는 다섯
 * 중에 없다. 돌려 쓰면 표현할 수 없어야 할 조합이 표현 가능해진다.
 */
public enum AuditTargetType {
  USER,
  COMMENT
}
