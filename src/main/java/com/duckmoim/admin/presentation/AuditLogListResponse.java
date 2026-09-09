package com.duckmoim.admin.presentation;

import com.duckmoim.admin.domain.AuditLogCursor;
import com.duckmoim.admin.service.AuditLogSlice;
import java.util.List;

/**
 * 감사 로그 목록 응답 (AD-05).
 *
 * <p>커서 페이지네이션 응답은 {@code items} · {@code nextCursor} · {@code hasNext} 셋이다 (API-컨벤션.md 「공통 응답
 * 형식」). 성공 응답을 {@code data} 로 감싸지 않는다.
 */
public record AuditLogListResponse(
    List<AuditLogItemResponse> items, String nextCursor, boolean hasNext) {

  static AuditLogListResponse from(AuditLogSlice slice) {
    return new AuditLogListResponse(
        slice.items().stream().map(AuditLogItemResponse::from).toList(),
        encoded(slice.nextCursor()),
        slice.hasNext());
  }

  private static String encoded(AuditLogCursor cursor) {
    return cursor == null ? null : cursor.encode();
  }
}
