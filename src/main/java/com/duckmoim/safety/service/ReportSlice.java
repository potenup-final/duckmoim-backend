package com.duckmoim.safety.service;

import com.duckmoim.safety.domain.ReportCursor;
import java.util.List;

/**
 * 백오피스 신고 목록 한 페이지 (AD-02).
 *
 * <p>{@code Slice}·{@code Page} 를 service 시그니처에 노출하지 않는다는 규칙(아키텍처-컨벤션.md 「service」)에 따라 둔 결과 객체다.
 * {@code AuditLogSlice} 와 같은 자리다.
 *
 * @param nextCursor 마지막 페이지면 null 이다
 */
public record ReportSlice(List<ReportView> items, ReportCursor nextCursor, boolean hasNext) {}
