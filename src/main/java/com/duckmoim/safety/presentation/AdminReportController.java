package com.duckmoim.safety.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.safety.service.ReportHandleService;
import com.duckmoim.safety.service.ReportQueryService;
import com.duckmoim.safety.service.ReportSlice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 백오피스의 신고 큐 (AD-02 · AD-03).
 *
 * <p><b>1차 안전장치가 신고뿐이라</b> 접수만 있고 처리 창구가 없으면 그 장치가 형식만 남는다. 이 컨트롤러가 그 창구다.
 *
 * <p><b>권한 판정이 이 클래스에 없다.</b> API-설계.md 「2-7. 백오피스 (Admin)」가 <i>"검증은 인터셉터 한 곳에서 {@code
 * /api/v1/admin/**} 전체에 건다. 컨트롤러마다 어노테이션을 흩뿌리지 않는다"</i> 고 정했다. 관문 판정은 {@code SecurityConfig} 와
 * {@code EndpointGradeTest} 의 권한 표가 지킨다.
 *
 * <p><b>조회와 처리가 한 클래스에 있다.</b> 서비스는 {@code ReportQueryService} 와 {@code ReportHandleService} 로 갈려
 * 있지만, 컨트롤러까지 나누면 같은 {@code @RequestMapping} 을 가진 클래스가 둘이 되어 이 리소스에 무엇이 열려 있는지 한눈에 안 보인다.
 */
@Tag(name = "백오피스 신고", description = "신고 큐 조회와 처리")
@RestController
@RequestMapping("/api/v1/admin/reports")
@RequiredArgsConstructor
public class AdminReportController {

  private final ReportQueryService reportQueryService;
  private final ReportHandleService reportHandleService;

  /**
   * 신고 큐를 읽는다 (AD-02).
   *
   * <p>최신순 고정이고 처리 상태로 거른다. <b>댓글 본문은 실리지 않는다</b> — {@code secret} 이 true 면 화면이 「본문 보기」를 따로 눌러
   * CM-17 경로를 부르고, 그 호출마다 감사 로그가 남는다.
   */
  @Operation(summary = "신고 목록 조회", description = "최신순이다. 처리 상태로 거를 수 있고, 생략하면 전량이다.")
  @GetMapping
  public AdminReportListResponse getReports(AdminReportListRequest request) {
    ReportSlice slice = reportQueryService.findReports(request.toQuery());

    return AdminReportListResponse.from(slice);
  }

  /**
   * 신고를 처리한다 (AD-03).
   *
   * <p><b>본문 없이 200 이다.</b> API-컨벤션.md 「Status Code 규칙」이 명령 성공을 200 으로 정했고, 처리하고 나면 그 건이 상태 필터 밖으로
   * 나가 화면이 어차피 목록을 다시 읽는다.
   *
   * <p>{@code AuthUser} 를 받는 것은 인가가 아니라 <b>이력</b> 때문이다. 누가 처리했는지가 {@code Report} 에 남는다 (도메인 1.1
   * 각주).
   */
  @Operation(
      summary = "신고 처리",
      description = "PENDING → PROCESSING → RESOLVED. 직행도 된다. 이미 종결된 건은 409 다.")
  @PatchMapping("/{reportId}")
  public void handleReport(
      @PathVariable Long reportId,
      @AuthenticationPrincipal AuthUser authUser,
      @Valid @RequestBody AdminReportHandleRequest request) {

    reportHandleService.handle(request.toCommand(reportId, authUser.userId()));
  }
}
