package com.duckmoim.safety.presentation;

/**
 * 제재를 건 결과 (AD-04).
 *
 * <p><b>제재 번호만 돌려준다.</b> 해제 경로가 {@code DELETE .../sanctions/{sanctionId}} 라 관리자가 방금 건 제재를 풀려면 그 번호가
 * 필요하다. 목록 조회가 없는 지금은 이 응답이 번호를 아는 유일한 통로다.
 *
 * <p>나머지 값은 돌려주지 않는다 — 방금 보낸 것과 같고, 화면은 {@code /users/me} 나 백오피스 화면에서 다시 읽는다.
 */
public record AdminSanctionResponse(Long sanctionId) {}
