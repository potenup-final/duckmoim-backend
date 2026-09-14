package com.duckmoim.safety.presentation;

import com.duckmoim.safety.service.SanctionListService;
import com.duckmoim.safety.service.SanctionSlice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 지금 제재 중인 회원 (AD-10).
 *
 * <p><b>{@code /admin/users/{userId}/sanctions} 아래가 아니다.</b> 그 경로는 「이 회원에게 조치한다」는 뜻이고 (AD-04), 이
 * 목록이 훑는 것은 회원이 아니라 활성 제재 전량이다. {@code /admin/reports} · {@code /admin/audit-logs} 와 같은 자리에 둔다.
 *
 * <p><b>그래서 {@code AdminSanctionController} 와 클래스를 나눴다.</b> 같은 클래스에 담으면 {@code @RequestMapping} 이
 * 하나인데 경로가 둘이 되어, 이 리소스에 무엇이 열려 있는지가 메서드마다 흩어진다.
 *
 * <p><b>감사 로그로 대신할 수 없다</b> (명세서 AD-10). {@code SANCTION} 과 {@code RELEASE} 가 함께 쌓일 뿐이라 현재 상태를 알려면
 * 로그를 재생해야 한다.
 *
 * <p><b>권한 판정이 이 클래스에 없다.</b> API-설계.md 「2-7. 백오피스 (Admin)」가 <i>"검증은 인터셉터 한 곳에서 {@code
 * /api/v1/admin/**} 전체에 건다. 컨트롤러마다 어노테이션을 흩뿌리지 않는다"</i> 고 정했다. 관문 판정은 {@code SecurityConfig} 와
 * {@code EndpointGradeTest} 의 권한 표가 지킨다.
 */
@Tag(name = "백오피스 제재 목록", description = "지금 제재 중인 회원 조회")
@RestController
@RequestMapping("/api/v1/admin/sanctions")
@RequiredArgsConstructor
public class AdminSanctionListController {

  private final SanctionListService sanctionListService;

  /**
   * 제재 중인 회원을 훑는다 (AD-10).
   *
   * <p>만료 임박순 고정이고 종류로 거른다. <b>활성 제재만 담긴다</b> — 푼 제재와 만료된 제재는 나오지 않는다. 제재 이력을 보는 경로가 아니다.
   *
   * <p><b>{@code AuthUser} 를 받지 않는다.</b> 인가는 관문이 보고, 조회라 감사 로그의 행위자도 필요하지 않다.
   */
  @Operation(summary = "제재 중인 회원 목록", description = "만료 임박순이다. 종류로 거를 수 있고, 생략하면 전량이다.")
  @GetMapping
  public AdminSanctionListResponse getSanctions(AdminSanctionListRequest request) {
    SanctionSlice slice =
        sanctionListService.findSanctions(
            request.kind(), request.decodedCursor(), request.sizeOrDefault());

    return AdminSanctionListResponse.from(slice);
  }
}
