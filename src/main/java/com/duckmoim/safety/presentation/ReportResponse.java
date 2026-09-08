package com.duckmoim.safety.presentation;

/**
 * 접수된 신고.
 *
 * <p><b>id 하나다.</b> 신고자가 자기 신고를 다시 조회하는 화면이 1차에 없고 (백오피스만 본다), 화면은 시트를 닫는 것으로 끝난다 — <i>"화면은 이동하지 않고
 * 시트로 끝난다 (SF-02)"</i>. 접수됐다는 사실 말고 돌려줄 것이 없다.
 */
public record ReportResponse(Long id) {}
