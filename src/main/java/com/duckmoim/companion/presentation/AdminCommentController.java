package com.duckmoim.companion.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.companion.service.AdminCommentReadService;
import com.duckmoim.companion.service.AdminCommentView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 백오피스의 댓글 본문 열람 (CM-17).
 *
 * <p><b>경로는 admin 이지만 클래스는 companion 에 있다.</b> 읽는 것이 {@code Comment} 애그리게이트이고 STAR-79 가 의존 방향을 「각
 * 컨텍스트 → admin」 으로 합의했다. admin 에 두면 그 방향이 뒤집힌다.
 *
 * <p><b>권한 판정이 이 클래스에 없다.</b> API-설계.md 「2-7. 백오피스 (Admin)」가 <i>"검증은 인터셉터 한 곳에서 {@code
 * /api/v1/admin/**} 전체에 건다. 컨트롤러마다 어노테이션을 흩뿌리지 않는다"</i> 고 정했다 — 하나를 빠뜨렸을 때 아무도 모르고, <b>그 하나가 비밀 댓글
 * 본문일 수 있다.</b> 관문 판정은 {@code SecurityConfig} 와 {@code EndpointGradeTest} 의 권한 표가 지킨다.
 *
 * <p>{@code AuthUser} 를 받는 것은 인가가 아니라 <b>기록</b> 때문이다. 누가 열었는지가 감사 로그의 행위자다 (AD-05).
 */
@Tag(name = "백오피스 댓글", description = "댓글 본문 열람")
@RestController
@RequestMapping("/api/v1/admin/comments/{commentId}")
@RequiredArgsConstructor
public class AdminCommentController {

  private final AdminCommentReadService adminCommentReadService;

  /**
   * 댓글 본문을 열어 본다 (CM-17).
   *
   * <p><b>조회인데 부수 효과가 있다.</b> 호출마다 감사 로그가 남는다 (AD-05) — 화면 계약이 신고 목록에 본문을 미리 싣지 않은 이유가 그것이다. <i>"미리
   * 실으면 열람 시점을 기록할 수 없기 때문이다."</i>
   *
   * @param reportId 어느 신고를 처리하다 열었는지. 화면 계약이 이 호출을 「신고 목록에서 본문 보기를 누를 때」로 정해 두었다. 선택이다
   */
  @Operation(summary = "댓글 본문 열람", description = "비밀 댓글도 본문을 준다. 호출마다 감사 로그가 남는다.")
  @GetMapping
  public AdminCommentResponse getComment(
      @PathVariable Long commentId,
      @RequestParam(required = false) Long reportId,
      @AuthenticationPrincipal AuthUser authUser) {

    AdminCommentView view = adminCommentReadService.read(commentId, authUser.userId(), reportId);

    return AdminCommentResponse.from(view);
  }
}
