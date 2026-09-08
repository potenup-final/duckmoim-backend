package com.duckmoim.companion.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.companion.domain.CommentReadContext;
import com.duckmoim.companion.service.CommentCommandService;
import com.duckmoim.companion.service.CommentQueryService;
import com.duckmoim.companion.service.CommentSlice;
import com.duckmoim.companion.service.WrittenComment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "댓글", description = "댓글 작성과 조회")
@RestController
@RequestMapping("/api/v1/posts/{postId}/comments")
@RequiredArgsConstructor
public class CommentController {

  private final CommentCommandService commentCommandService;
  private final CommentQueryService commentQueryService;
  private final CommentItemAssembler commentItemAssembler;

  /**
   * 모집글의 댓글을 읽는다 (CM-06 · CM-07 · CM-18 · CM-20).
   *
   * <p><b>권한이 {@code PUBLIC} 이라 {@code authUser} 가 null 일 수 있다.</b> 비회원도 공개 댓글 본문을 본다 (CM-20). 토큰을
   * 보내면 인증 필터가 채워 주므로, 로그인한 사람은 자기 비밀 댓글과 액션 버튼을 함께 받는다.
   *
   * <p>본문을 보여줄지는 조립기가 판정한다. 컨트롤러는 <b>판정에 필요한 것을 모아 넘기는 일</b>만 한다 — 요청자와 방장, 그리고 대댓글이면 부모 댓글 작성자다
   * (도메인-모델링.md 「7.1 가시성과 권한」).
   */
  @Operation(summary = "댓글 목록 조회", description = "루트 시간순이고 대댓글은 부모와 함께 온다. 커서는 루트 댓글 기준이다.")
  @GetMapping
  public CommentListResponse getComments(
      @PathVariable Long postId,
      @AuthenticationPrincipal AuthUser authUser,
      CommentListRequest request) {

    CommentSlice slice = commentQueryService.findComments(request.toQuery(postId));
    CommentReadContext context =
        new CommentReadContext(requesterIdOf(authUser), slice.hostId(), null);

    List<CommentItemResponse> items =
        slice.roots().stream()
            .map(root -> commentItemAssembler.assembleWithReplies(root, context))
            .toList();

    return CommentListResponse.of(items, slice);
  }

  /** 비회원이면 null 이다. 인증 필터가 토큰 없는 요청을 익명인 채로 통과시킨다 (STAR-41). */
  private static Long requesterIdOf(AuthUser authUser) {
    return authUser == null ? null : authUser.userId();
  }

  /**
   * 모집글에 댓글을 쓴다 (CM-01 · CM-02 · CM-03).
   *
   * <p>생성 성공도 200 이다 (API-컨벤션.md 「Status Code 규칙」). 201 을 쓰지 않는다.
   *
   * <p><b>{@code authUser} 는 여기서 null 이 될 수 없다.</b> 이 경로가 SIGNUP 등급으로 선언되어 있어 (STAR-41 의 {@code
   * SecurityConfig}) 익명 요청은 401, 가입 미완료는 403 으로 관문에서 끝난다. 등급 판정 자체는 {@code EndpointGradeTest} 의 권한
   * 표가 지킨다 — 이 컨트롤러가 다시 확인하면 같은 규칙이 두 곳에서 관리된다.
   *
   * <p>요청자를 요청 본문으로 받지 않는 것이 남의 이름으로 쓰는 것을 막는 유일한 장치다.
   */
  @Operation(
      summary = "댓글 작성",
      description = "parentId 를 주면 대댓글이고 루트 댓글에만 붙는다. secret 은 작성할 때만 정한다.")
  @PostMapping
  public CommentResponse writeComment(
      @PathVariable Long postId,
      @AuthenticationPrincipal AuthUser authUser,
      @Valid @RequestBody CommentWriteRequest request) {

    WrittenComment written =
        commentCommandService.write(request.toCommand(postId, authUser.userId()));

    return CommentResponse.from(written);
  }
}
