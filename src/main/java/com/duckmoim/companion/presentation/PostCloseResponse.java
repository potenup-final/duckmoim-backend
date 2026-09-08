package com.duckmoim.companion.presentation;

import com.duckmoim.companion.domain.ClosedReason;
import com.duckmoim.companion.domain.PostStatus;
import com.duckmoim.companion.service.ClosedCompanionPost;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 마감한 모집글의 응답 (PO-07).
 *
 * <p><b>{@code closedReason} 을 담는다.</b> 화면-계약.md 「상세 `GET /api/v1/posts/{postId}` — PO-11」이
 * <i>"closedReason 이 배지 문구를 정한다 — MANUAL 이면 「모집 완료」"</i> 라고 정했다. 상태만 주면 화면이 배지를 못 고르고 상세를 다시 불러야
 * 한다.
 *
 * <p>{@code CompanionPostResponse} 를 쓰지 않는 이유는 그 클래스에 적혀 있다 — 작성 직후에는 사유가 항상 {@code null} 이라 담지 않기로
 * 했다. 여기서 재사용하면 그 판단을 되돌리게 된다.
 */
public record PostCloseResponse(
    @Schema(description = "마감한 모집글의 번호", example = "12") Long id,
    @Schema(description = "마감 후의 상태. 항상 CLOSED 다", example = "CLOSED") PostStatus status,
    @Schema(description = "마감 사유. 방장 마감은 항상 MANUAL 이다", example = "MANUAL")
        ClosedReason closedReason) {

  static PostCloseResponse from(ClosedCompanionPost closed) {
    return new PostCloseResponse(closed.id(), closed.status(), closed.closedReason());
  }
}
