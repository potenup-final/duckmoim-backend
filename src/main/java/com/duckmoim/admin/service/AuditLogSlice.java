package com.duckmoim.admin.service;

import com.duckmoim.admin.domain.AuditLogCursor;
import java.util.List;

/**
 * 감사 로그 한 페이지 (AD-05).
 *
 * <p>{@code Slice}·{@code Page} 를 service 시그니처에 노출하지 않는다는 규칙(아키텍처-컨벤션.md 「service」)에 따라 둔 결과 객체다.
 * {@code MyCommentSlice} 와 같은 자리다.
 *
 * @param nextCursor 마지막 페이지면 null 이다
 */
public record AuditLogSlice(List<AuditLogView> items, AuditLogCursor nextCursor, boolean hasNext) {}
