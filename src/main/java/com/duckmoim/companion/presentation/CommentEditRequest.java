package com.duckmoim.companion.presentation;

import com.duckmoim.companion.service.CommentEditCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 댓글 수정 요청 (CM-09).
 *
 * <p><b>화면 계약에 수정 payload 절이 없어서 여기서 모양을 정했다.</b> 작성 payload 셋 중 {@code parentId} 를 뺀 둘이다 — 부모는 작성할
 * 때 정해지고 옮길 수 없다.
 *
 * <p>{@code secret} 을 <b>받는 이유가 검증 기준이다.</b> CM-09 가 「비밀 변경 400」 을 요구하는데 아예 안 받으면 400 을 낼 자리가 없다.
 * 저장값과 다르면 400 이고, 안 보내거나 같은 값이면 통과다.
 */
public record CommentEditRequest(
    @Schema(description = "본문. 500자 이하", example = "저 못 가게 됐어요")
        @NotBlank(message = "본문을 입력해 주세요.")
        @Size(max = 500, message = "본문은 500자 이하여야 합니다.")
        String content,
    @Schema(description = "비밀 여부. 바꿀 수 없다 — 저장값과 다르면 400", nullable = true) Boolean secret) {

  CommentEditCommand toCommand(Long commentId, Long requesterId) {
    return new CommentEditCommand(commentId, requesterId, content, secret);
  }
}
