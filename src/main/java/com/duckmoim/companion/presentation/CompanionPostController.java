package com.duckmoim.companion.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.companion.service.CompanionPostCommandService;
import com.duckmoim.companion.service.CompanionPostQueryService;
import com.duckmoim.companion.service.WrittenCompanionPost;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "모집글", description = "동행 모집글 작성과 조회")
@RestController
@RequestMapping("/api/v1/posts")
@RequiredArgsConstructor
public class CompanionPostController {

  private final CompanionPostCommandService companionPostCommandService;
  private final CompanionPostQueryService companionPostQueryService;

  /**
   * 모집글 목록을 조회한다 (PO-08).
   *
   * <p>인증이 필요 없다 (API-설계.md 「2-4. 모집글 (Companion)」 · {@code PUBLIC}).
   *
   * <p><b>정렬을 파라미터로 받지 않는다.</b> 만남시각 임박순 고정이고, 커서가 그 정렬 키를 담고 있어서 정렬이 바뀌면 이미 발급한 커서가 무의미해진다.
   *
   * <p><b>검색은 이 경로에 없다.</b> PO-13 목록 검색은 1차에서 브라우저가 거른다 (API-설계.md 「2-4. 모집글 (Companion)」).
   */
  @Operation(summary = "모집글 목록 조회", description = "상태로 거르고 커서로 페이지를 넘긴다. 만남시각 임박순이다.")
  @GetMapping
  public CompanionPostListResponse getPosts(CompanionPostListRequest request) {
    return CompanionPostListResponse.from(companionPostQueryService.findPosts(request.toQuery()));
  }

  /**
   * 모집글 한 건을 조회한다 (PO-11).
   *
   * <p><b>비인증 요청에도 본문을 포함해 200 이다.</b> 그것이 PO-11 의 검증 기준이고, 요청자를 받지 않는 것이 그 계약을 코드로 못박는다 —
   * {@code @AuthenticationPrincipal} 을 받아두면 언젠가 그것으로 본문을 가르는 분기가 붙는다.
   *
   * <p><b>경로 변수는 숫자 PK 다.</b> 행사와 다르다 — 행사만 외부 식별자로 URL 을 만든다 (API-설계.md 「2-3. 행사 (Catalog)」).
   *
   * <p><b>댓글 본문은 여기 없다.</b> 별도 조회다 (CM-06). 댓글 수만 함께 나간다.
   */
  @Operation(summary = "모집글 상세 조회", description = "본문 전체와 댓글 수를 함께 준다. 비회원도 볼 수 있다.")
  @GetMapping("/{postId}")
  public CompanionPostDetailResponse getPost(@PathVariable Long postId) {
    return CompanionPostDetailResponse.from(companionPostQueryService.findPost(postId));
  }

  /**
   * 모집글을 연다 (PO-01 · PO-02 · PO-03 · PO-05).
   *
   * <p>생성 성공도 200 이다 (API-컨벤션.md 「Status Code 규칙」). 201 을 쓰지 않는다.
   *
   * <p><b>{@code authUser} 는 여기서 null 이 될 수 없다.</b> 이 경로가 SIGNUP 등급으로 선언되어 있어 익명 요청은 401, 가입 미완료는
   * 403 으로 관문에서 끝난다. 등급 판정 자체는 {@code EndpointGradeTest} 의 권한 표가 지킨다 — 이 컨트롤러가 다시 확인하면 같은 규칙이 두 곳에서
   * 관리된다.
   *
   * <p>방장을 요청 본문으로 받지 않는 것이 남의 이름으로 쓰는 것을 막는 유일한 장치다.
   */
  @Operation(
      summary = "모집글 작성",
      description = "필수는 제목 · 만남시각 · 만남지점 셋이다. 행사를 고르면 만남시각이 행사 종료일 안인지 함께 본다.")
  @PostMapping
  public CompanionPostResponse writePost(
      @AuthenticationPrincipal AuthUser authUser,
      @Valid @RequestBody CompanionPostWriteRequest request) {

    WrittenCompanionPost written =
        companionPostCommandService.create(request.toCommand(authUser.userId()));

    return CompanionPostResponse.from(written);
  }
}
