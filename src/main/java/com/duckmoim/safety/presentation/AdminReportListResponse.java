package com.duckmoim.safety.presentation;

import com.duckmoim.safety.domain.ReportCursor;
import com.duckmoim.safety.service.ReportSlice;
import java.util.List;

/**
 * 백오피스 신고 목록 응답 (AD-02).
 *
 * <p>커서 페이지네이션 응답은 {@code items} · {@code nextCursor} · {@code hasNext} 셋이다 (API-컨벤션.md 「공통 응답
 * 형식」). 성공 응답을 {@code data} 로 감싸지 않는다.
 */
public record AdminReportListResponse(
    List<AdminReportItemResponse> items, String nextCursor, boolean hasNext) {

  static AdminReportListResponse from(ReportSlice slice) {
    return new AdminReportListResponse(
        slice.items().stream().map(AdminReportItemResponse::from).toList(),
        encoded(slice.nextCursor()),
        slice.hasNext());
  }

  private static String encoded(ReportCursor cursor) {
    return cursor == null ? null : cursor.encode();
  }
}
