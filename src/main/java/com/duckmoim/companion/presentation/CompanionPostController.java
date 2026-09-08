package com.duckmoim.companion.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.companion.service.CompanionPostCommandService;
import com.duckmoim.companion.service.WrittenCompanionPost;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
