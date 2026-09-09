package com.duckmoim.companion.presentation;

import com.duckmoim.companion.service.CommentWriteCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 댓글 작성 요청 (화면-계약.md 「작성 `POST /api/v1/posts/{postId}/comments` — CM-01」).
 *
 * <p>세 필드가 계약이다. boolean 에 is 접두어를 붙이지 않는다 (API-컨벤션.md 「필드 표기 규칙」).
 *
 * <p>500자 판정을 여기서만 한다. API-컨벤션.md 「Validation 규칙」이 단순 형식 검증을 Bean Validation 으로 정했고, 도메인과 DB 에 같은
 * 숫자를 겹쳐 두면 한 곳만 고치는 날이 온다.
 */
public record CommentWriteRequest(
    @Schema(description = "본문. 500자 이하", example = "저 갈게요!")
        @NotBlank(message = "본문을 입력해 주세요.")
        @Size(max = 500, message = "본문은 500자 이하여야 합니다.")
        String content,
    @Schema(description = "부모 댓글 id. 주면 대댓글이고 루트 댓글에만 붙일 수 있다", nullable = true) Long parentId,
    @Schema(description = "비밀 댓글 여부. 작성할 때만 정하고 수정으로 바꿀 수 없다") boolean secret) {

  CommentWriteCommand toCommand(Long postId, Long authorId) {
    return new CommentWriteCommand(postId, authorId, parentId, content, secret);
  }
}
