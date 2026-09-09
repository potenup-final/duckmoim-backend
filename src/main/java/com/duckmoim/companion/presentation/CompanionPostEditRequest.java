package com.duckmoim.companion.presentation;

import com.duckmoim.companion.service.CompanionPostEditCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * 모집글 수정 요청 (PO-06).
 *
 * <p><b>화면 계약에 수정 payload 절이 없어서 여기서 모양을 정했다.</b> 화면-계약.md 가 PO-06 을 <i>「권장 등급. 화면이 없다」</i> 로만 적었다.
 * {@code CommentEditRequest} 가 같은 상황을 같게 처리한 선례가 있고, 그 방식은 <b>작성 payload 에서 옮길 수 없는 것만 빼는 것</b>이다.
 *
 * <p>여기서 뺀 것이 없다. 작성의 여섯 필드가 다 있다 — {@code title} · {@code content} · {@code eventId} · {@code
 * capacity} · {@code meetAt} · {@code meetPoint}. 방장은 인증에서 나오고 상태는 마감 경로만 건드리므로 애초에 작성 payload 에도
 * 없다.
 *
 * <p><b>필수 셋도 작성과 같다.</b> 부분 수정이 아니라 전량 치환이라 (근거는 {@code CompanionPost.editByHost}) 제목 · 만남시각 ·
 * 만남지점을 안 보내는 것은 「그대로 두라」가 아니라 「비우라」가 되고, 그것은 PO-01 이 필수로 정한 셋을 지우는 요청이다.
 *
 * <p>검증 숫자는 작성 요청과 같은 값을 쓴다. 화면-계약.md 「작성 `POST /api/v1/posts` — PO-01」이 화면이 먼저 거를 것을 한 벌만 적었고, 수정
 * 폼도 같은 폼이다.
 *
 * @param eventId 행사의 <b>외부 식별자</b>다 (`pg_8417`). 숫자 PK 가 아니다. 안 보내면 행사를 뗀다
 */
public record CompanionPostEditRequest(
    @Schema(description = "제목. 1~40자", example = "에이티즈 팝업 오후에 가실 분")
        @NotBlank(message = "제목을 입력해 주세요.")
        @Size(max = 40, message = "제목은 40자 이하여야 합니다.")
        String title,
    @Schema(description = "본문. 500자 이하", nullable = true, example = "오전이 막혀서 시간을 옮겼어요")
        @Size(max = 500, message = "본문은 500자 이하여야 합니다.")
        String content,
    @Schema(description = "고른 행사의 외부 식별자. 안 보내면 행사를 뗀다", nullable = true, example = "pg_8417")
        String eventId,
    @Schema(
            description = "정원. 안 보내면 정원을 지운다. 값이 있으면 2~6",
            nullable = true,
            minimum = "2",
            maximum = "6",
            example = "4")
        Integer capacity,
    @Schema(description = "만남시각. 오프셋을 포함한다", example = "2026-09-14T15:00:00+09:00")
        @NotNull(message = "만남시각을 입력해 주세요.")
        OffsetDateTime meetAt,
    @Schema(description = "만남 지점. 장소명과 좌표가 모두 필요하다")
        @NotNull(message = "지도에서 만남 지점을 찍어 주세요.")
        @Valid
        MeetPointRequest meetPoint) {

  CompanionPostEditCommand toCommand(Long postId, Long requesterId) {
    return new CompanionPostEditCommand(
        postId,
        requesterId,
        title,
        content,
        eventId,
        meetAt,
        meetPoint.place(),
        meetPoint.lat(),
        meetPoint.lng(),
        capacity);
  }
}
