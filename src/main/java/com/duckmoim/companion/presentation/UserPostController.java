package com.duckmoim.companion.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.companion.service.UserPostQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 유저가 쓴 모집글 두 경로 (AU-09 · AU-10).
 *
 * <p><b>경로는 회원 섹션인데 코드가 {@code companion} 에 있다.</b> 돌려주는 것이 모집글이라 그렇다 — 게이트가 {@code
 * LAYER_DEPENDENCY} 로 service 를 presentation 에서만 참조하게 막아서 {@code identity} 의 컨트롤러가 이 조회 서비스를 부를 수
 * 없다. {@code MyCommentController} 가 {@code /users/me/comments} 를 같은 이유로 여기 두었고, 그 javadoc 이 <i>"「내
 * 모집글」 탭은 별도 컨트롤러로 붙는다"</i> 로 이 파일을 가리켜 두었다.
 *
 * <p><b>핸들러를 둘로 둔다.</b> API 설계가 <i>"경로를 합치면 그 분기가 한 핸들러 안으로 들어온다"</i> 로 갈라 놨다. 지금은 두 응답의 데이터가
 * 같지만(모집글에 비공개 개념이 없다 · 결정 D-3) 분기가 생기는 날 놓을 자리가 필요하다. 조회는 하나를 쓰므로 파일은 나누지 않았다.
 */
@Tag(name = "내 활동 내역", description = "내가 쓴 모집글과 남이 쓴 모집글")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserPostController {

  private final UserPostQueryService userPostQueryService;

  /**
   * 내가 쓴 모집글 (AU-10 「내 모집글 탭」).
   *
   * <p>등급이 {@code SIGNUP} 이다 — 가입 미완료 유저는 모집글을 쓸 수 없어 내역이 항상 비어 있다 (API 설계 2-2).
   *
   * <p><b>회원번호를 경로에서 받지 않는다.</b> {@code @AuthenticationPrincipal} 에서 꺼낸다 — 남의 내역을 내 것처럼 부르는 경로를 만들지
   * 않는 유일한 장치다.
   */
  @Operation(summary = "내 모집글 내역 조회", description = "작성 최신순이다. 마감된 글도 함께 온다.")
  @GetMapping("/me/posts")
  public UserPostListResponse getMyPosts(
      @AuthenticationPrincipal AuthUser authUser, UserPostListRequest request) {

    return UserPostListResponse.from(
        userPostQueryService.findUserPosts(request.toQuery(authUser.userId())));
  }

  /**
   * 그 유저가 쓴 모집글 (AU-09).
   *
   * <p><b>등급이 {@code PUBLIC} 이다.</b> 비회원이 부른다 — 만나기 전에 상대가 어떤 모집을 열었는지 보는 화면이다.
   *
   * <p><b>없는 회원번호로 물으면 200 과 빈 페이지다.</b> 404 를 내려면 회원 조회가 한 번 더 붙는데, 없는 회원과 글이 없는 회원의 응답이 어차피 같아 존재
   * 여부가 새지 않는다. 프로필 단건이 404 를 내므로 화면은 그쪽으로 판단한다.
   *
   * <p><b>이 매핑이 {@code /me/posts} 보다 넓다.</b> 스프링이 리터럴 경로를 변수 경로보다 먼저 골라 그쪽으로 간다. 우선순위가 뒤집히면 남의 내역이
   * 열리는 것이 아니라 <b>{@code "me"} 를 {@code Long} 으로 바꾸다 터진다</b> — 조용히 뒤집히지 않게 테스트로 못박아 두었다.
   */
  @Operation(summary = "유저가 쓴 모집글 조회", description = "작성 최신순이다. 없는 회원이면 빈 페이지가 온다.")
  @GetMapping("/{userId}/posts")
  public UserPostListResponse getUserPosts(@PathVariable Long userId, UserPostListRequest request) {

    return UserPostListResponse.from(userPostQueryService.findUserPosts(request.toQuery(userId)));
  }
}
