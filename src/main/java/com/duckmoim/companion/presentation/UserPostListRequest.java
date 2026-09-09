package com.duckmoim.companion.presentation;

import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.common.exception.CommonErrorCode;
import com.duckmoim.companion.domain.UserPostCursor;
import com.duckmoim.companion.domain.UserPostListQuery;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 유저가 쓴 모집글 목록 요청 (AU-09 · AU-10).
 *
 * <p><b>{@code status} 를 받지 않는다.</b> 목록(PO-08)에는 「모집중 / 전체」 필터가 있지만 프로필 탭에는 그 요구가 없고, 마감된 글도 내역에
 * 남아야 한다.
 *
 * <p><b>소유자를 요청에서 받지 않는다.</b> {@code toQuery(Long)} 로 넘긴다 — 내 내역은 인증 주체에서, 남의 것은 경로 변수에서 온다. 요청
 * 파라미터로 두면 남의 내역을 내 것처럼 부를 수 있다.
 *
 * <p>커서 판독 실패를 {@code INVALID_INPUT} 400 으로 옮긴다 (API 컨벤션 「커서 디코딩 실패 포함」).
 */
public record UserPostListRequest(
    @Schema(description = "이전 응답의 nextCursor. 첫 페이지는 생략한다", nullable = true) String cursor,
    @Schema(description = "기본 20, 최대 50") Integer size) {

  UserPostListQuery toQuery(Long hostId) {
    return new UserPostListQuery(hostId, decodedCursor(), size == null ? 0 : size);
  }

  private UserPostCursor decodedCursor() {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }

    try {
      return UserPostCursor.decode(cursor);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(CommonErrorCode.INVALID_INPUT);
    }
  }
}
