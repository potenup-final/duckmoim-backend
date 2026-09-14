package com.duckmoim.safety.presentation;

import com.duckmoim.safety.domain.SanctionCursor;
import com.duckmoim.safety.service.SanctionSlice;
import java.util.List;

/**
 * 백오피스 제재 목록 응답 (AD-10).
 *
 * <p>커서 페이지네이션 응답은 {@code items} · {@code nextCursor} · {@code hasNext} 셋이다 (API-컨벤션.md 「공통 응답
 * 형식」). 성공 응답을 {@code data} 로 감싸지 않고, 총 건수도 싣지 않는다.
 */
public record AdminSanctionListResponse(
    List<AdminSanctionItemResponse> items, String nextCursor, boolean hasNext) {

  static AdminSanctionListResponse from(SanctionSlice slice) {
    return new AdminSanctionListResponse(
        slice.items().stream().map(AdminSanctionItemResponse::from).toList(),
        encoded(slice.nextCursor()),
        slice.hasNext());
  }

  private static String encoded(SanctionCursor cursor) {
    return cursor == null ? null : cursor.encode();
  }
}
