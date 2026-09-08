package com.duckmoim.companion.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.companion.service.CommentCommandService;
import com.duckmoim.companion.service.WrittenComment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 댓글 한 건을 다루는 경로 (CM-09 · CM-10 · CM-11).
 *
 * <p>작성과 목록은 모집글 하위 경로라 {@link PostCommentController} 에 있다. 뿌리가 달라 한 클래스의 {@code @RequestMapping}
 * 으로 묶을 수 없다.
 *
 * <p>둘 다 {@code SIGNUP} 등급이다 (API-설계.md 「2-5. 댓글 (Companion)」). 익명은 401, 가입 미완료는 403 으로 관문에서 끝나므로
 * {@code authUser} 가 여기서 null 이 될 수 없다.
 */
@Tag(name = "댓글", description = "댓글 수정과 삭제")
@RestController
@RequestMapping("/api/v1/comments/{commentId}")
@RequiredArgsConstructor
public class CommentController {

  private final CommentCommandService commentCommandService;

  /**
   * 댓글 본문을 고친다 (CM-09).
   *
   * <p>응답이 작성과 같은 모양이다. STAR-54 가 그것을 최소 여섯 필드로 정한 이유가 여기도 그대로다 — {@code availableActions} 와 작성자 블록
   * 조립은 조회 경로 한 곳에만 둔다 (도메인-모델링.md 「7.1 가시성과 권한」).
   */
  @Operation(summary = "댓글 수정", description = "작성자 본인만 할 수 있다. 비밀 여부는 바꿀 수 없다.")
  @PatchMapping
  public CommentResponse editComment(
      @PathVariable Long commentId,
      @AuthenticationPrincipal AuthUser authUser,
      @Valid @RequestBody CommentEditRequest request) {

    WrittenComment edited =
        commentCommandService.edit(request.toCommand(commentId, authUser.userId()));

    return CommentResponse.from(edited);
  }

  /**
   * 댓글을 소프트 삭제한다 (CM-10 · CM-11).
   *
   * <p><b>본문 없이 200 이다.</b> API-컨벤션.md 「Status Code 규칙」이 명령 성공을 200 으로 정했고, 지운 댓글의 무엇을 돌려줄 이유가 없다 —
   * 목록에 자리표시자로 남을지는 조회가 판정한다 (CM-11).
   */
  @Operation(summary = "댓글 삭제", description = "작성자 또는 방장이 할 수 있다. 소프트 삭제다.")
  @DeleteMapping
  public void deleteComment(
      @PathVariable Long commentId, @AuthenticationPrincipal AuthUser authUser) {

    commentCommandService.delete(commentId, authUser.userId());
  }
}
