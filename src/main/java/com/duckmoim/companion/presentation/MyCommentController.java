package com.duckmoim.companion.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.companion.service.MyCommentQueryService;
import com.duckmoim.companion.service.MyCommentSlice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 활동 내역의 댓글 탭 (CM-16 · AU-10).
 *
 * <p><b>경로가 {@code /users} 아래인데 코드가 companion 에 있다.</b> 내려주는 것이 댓글이라 그렇다 — 모집글 제목을 얻는 조인도, 본문 열람
 * 판정도 이 컨텍스트 안에서 끝난다. 「내 모집글」 탭({@code /users/me/posts}, AU-10)은 별도 컨트롤러로 붙는다.
 *
 * <p>권한이 {@code SIGNUP} 인 이유 — 가입 미완료 유저는 댓글을 쓸 수 없어 내역이 항상 비어 있다 (AU-07 · API-설계.md 「2-2. 회원
 * (Identity)」). 관문 판정은 {@code SecurityConfig} 와 {@code EndpointGradeTest} 의 권한 표가 지킨다.
 */
@Tag(name = "내 활동 내역", description = "내가 쓴 댓글")
@RestController
@RequestMapping("/api/v1/users/me/comments")
@RequiredArgsConstructor
public class MyCommentController {

  private final MyCommentQueryService myCommentQueryService;
  private final CommentItemAssembler commentItemAssembler;

  /**
   * 내가 쓴 댓글을 읽는다 (CM-16).
   *
   * <p><b>{@code authUser} 는 null 이 될 수 없다.</b> 이 경로가 {@code SIGNUP} 등급이라 익명 요청은 401, 가입 미완료는 403
   * 으로 관문에서 끝난다.
   *
   * <p>본문을 보여줄지는 조립기가 판정한다 (도메인-모델링.md 「7.1 가시성과 권한」). 컨트롤러는 요청자를 넘기는 일만 한다 — 내 비밀 댓글은 내가 쓴 것이라 본문이
   * 함께 온다.
   */
  @Operation(summary = "내 댓글 내역 조회", description = "작성 최신순이다. 내 비밀 댓글도 본문이 함께 온다. 지운 댓글은 오지 않는다.")
  @GetMapping
  public MyCommentListResponse getMyComments(
      @AuthenticationPrincipal AuthUser authUser, MyCommentListRequest request) {

    MyCommentSlice slice = myCommentQueryService.findMyComments(request.toQuery(authUser.userId()));

    List<MyCommentItemResponse> items =
        slice.items().stream()
            .map(item -> commentItemAssembler.assembleMine(item, authUser.userId()))
            .toList();

    return MyCommentListResponse.of(items, slice);
  }
}
