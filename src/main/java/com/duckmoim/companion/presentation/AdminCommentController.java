package com.duckmoim.companion.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.companion.service.AdminCommentCommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 백오피스가 댓글에 내리는 조치 (AD-07).
 *
 * <p><b>경로는 admin 이지만 클래스는 companion 에 있다.</b> 바꾸는 것이 {@code Comment} 애그리게이트이고 STAR-79 가 의존 방향을 「각
 * 컨텍스트 → admin」 으로 합의했다.
 *
 * <p><b>권한 판정이 이 클래스에 없다.</b> API-설계.md 「2-7. 백오피스 (Admin)」가 <i>"검증은 인터셉터 한 곳에서 {@code
 * /api/v1/admin/**} 전체에 건다. 컨트롤러마다 어노테이션을 흩뿌리지 않는다"</i> 고 정했다. 관문 판정은 {@code SecurityConfig} 와
 * {@code EndpointGradeTest} 의 권한 표가 지킨다.
 *
 * <p>{@code AuthUser} 를 받는 것은 인가가 아니라 <b>기록</b> 때문이다. 누가 가렸는지가 감사 로그의 행위자다 (AD-05).
 */
@Tag(name = "백오피스 댓글", description = "댓글 조치")
@RestController
@RequestMapping("/api/v1/admin/comments/{commentId}")
@RequiredArgsConstructor
public class AdminCommentController {

  private final AdminCommentCommandService adminCommentCommandService;

  /**
   * 댓글을 가린다 (AD-07).
   *
   * <p><b>본문 없는 명령이다.</b> 화면-계약.md 가 <i>"{@code ACTIVE → BLINDED} 전이 하나뿐이라 무엇을 바꿀지 실어 보낼 것이 없다"</i>
   * 고 정했다. 상태를 {@code PATCH} 로 넘기지 않는 것도 같은 문서의 판단이고, 모집글 마감({@code POST .../close})과 같은 모양이다.
   *
   * <p><b>응답도 없이 200 이다.</b> API-컨벤션.md 「Status Code 규칙」이 명령 성공을 200 으로 정했고, 결과 상태가 {@code BLINDED}
   * 하나로 정해져 있어 돌려줄 정보가 없다. 마감이 {@code PostCloseResponse} 를 주는 것은 그쪽에 {@code closedReason} 이라는 갈리는
   * 값이 있어서다.
   */
  @Operation(summary = "댓글 블라인드", description = "신고 처리 결과로 댓글을 가린다. 본문이 응답에서 사라지고 자리표시자만 남는다.")
  @PostMapping("/blind")
  public void blindComment(
      @PathVariable Long commentId, @AuthenticationPrincipal AuthUser authUser) {

    adminCommentCommandService.blind(commentId, authUser.userId());
  }
}
