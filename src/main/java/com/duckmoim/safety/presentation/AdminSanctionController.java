package com.duckmoim.safety.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.safety.service.SanctionCommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 백오피스가 유저에게 내리는 조치 (AD-04).
 *
 * <p>신고 처리(AD-03)의 조치 경로 둘 중 유저 축이다. 콘텐츠 축은 댓글 블라인드(AD-07)다.
 *
 * <p><b>권한 판정이 이 클래스에 없다.</b> API-설계.md 「2-7. 백오피스 (Admin)」가 <i>"검증은 인터셉터 한 곳에서 {@code
 * /api/v1/admin/**} 전체에 건다"</i> 고 정했다. 관문 판정은 {@code SecurityConfig} 와 {@code EndpointGradeTest} 의
 * 권한 표가 지킨다.
 *
 * <p>{@code AuthUser} 를 받는 것은 인가가 아니라 <b>감사 로그의 행위자</b> 때문이다 (AD-05).
 */
@Tag(name = "백오피스 제재", description = "유저 제재와 해제")
@RestController
@RequestMapping("/api/v1/admin/users/{userId}/sanctions")
@RequiredArgsConstructor
public class AdminSanctionController {

  private final SanctionCommandService sanctionCommandService;

  /**
   * 제재를 건다 (AD-04).
   *
   * <p>이미 활성 제재가 있으면 409 다 — 도메인 6장의 제재 축이 {@code NONE} 에서만 출발한다.
   */
  @Operation(summary = "유저 제재", description = "경고 / 나이 확인 / 기간 정지 / 영구 정지. 사유는 본인에게 보인다.")
  @PostMapping
  public AdminSanctionResponse sanction(
      @PathVariable Long userId,
      @AuthenticationPrincipal AuthUser authUser,
      @Valid @RequestBody AdminSanctionRequest request) {

    Long sanctionId = sanctionCommandService.sanction(request.toCommand(userId, authUser.userId()));

    return new AdminSanctionResponse(sanctionId);
  }

  /**
   * 제재를 푼다 (AD-04).
   *
   * <p><b>{@code DELETE} 인 이유</b> — {@code kind: NONE} 을 POST 하지 않는다. {@code Sanction} 은 대상 · 종류 ·
   * 기간 · 발효 · 만료 · 사유를 갖는 기록이고 {@code NONE} 은 그중 무엇도 아닌 「제재가 없다」는 상태라, 남길 기록이 없으니 생성이 아니라 삭제다
   * (API-설계.md 2-7).
   *
   * <p><b>행을 지우지는 않는다.</b> 요청이 {@code DELETE} 인 것과 저장이 어떻게 남는가는 다른 이야기다 — 같은 유저가 몇 번 제재받았는지는 백오피스가
   * 판단에 쓰는 재료다.
   *
   * <p>본문 없이 200 이다 (API-컨벤션.md 「Status Code 규칙」). 푼 결과가 「제재 없음」 하나로 정해져 있어 돌려줄 정보가 없다.
   */
  @Operation(summary = "제재 해제", description = "kind: NONE 을 POST 하지 않는다. 남길 기록이 없으니 삭제다.")
  @DeleteMapping("/{sanctionId}")
  public void release(
      @PathVariable Long userId,
      @PathVariable Long sanctionId,
      @AuthenticationPrincipal AuthUser authUser) {

    sanctionCommandService.release(userId, sanctionId, authUser.userId());
  }
}
