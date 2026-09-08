package com.duckmoim.safety.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.safety.service.ReportCommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 신고 접수 (SF-01 · SF-02 · SF-07 · CM-14).
 *
 * <p><b>엔드포인트가 하나다.</b> API-설계.md 「2-6. 신고 (Safety)」가 <i>"대상 종류만 다른 하나의 엔드포인트"</i> 로 정했다. 세 담당자가 각자
 * 만들면 같은 컨트롤러를 셋이 건드린다.
 *
 * <p>{@code SIGNUP} 등급이라 익명은 401, 가입 미완료는 403 으로 관문에서 끝난다. {@code authUser} 가 여기서 null 이 될 수 없다.
 */
@Tag(name = "신고", description = "유저 · 모집글 · 댓글 신고")
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

  private final ReportCommandService reportCommandService;

  /**
   * 신고를 접수한다.
   *
   * <p>생성 성공도 200 이다 (API-컨벤션.md 「Status Code 규칙」).
   *
   * <p>신고자를 요청 본문으로 받지 않는 것이 남의 이름으로 신고하는 것을 막는 장치다.
   */
  @Operation(summary = "신고 접수", description = "targetType 으로 대상이 갈린다. 사유는 대상별 조합표를 지켜야 한다.")
  @PostMapping
  public ReportResponse report(
      @AuthenticationPrincipal AuthUser authUser, @Valid @RequestBody ReportRequest request) {

    return new ReportResponse(reportCommandService.report(request.toCommand(authUser.userId())));
  }
}
