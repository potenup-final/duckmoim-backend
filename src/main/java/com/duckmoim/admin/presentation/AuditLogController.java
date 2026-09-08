package com.duckmoim.admin.presentation;

import com.duckmoim.admin.service.AuditLogQueryService;
import com.duckmoim.admin.service.AuditLogSlice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 백오피스의 감사 로그 (AD-05).
 *
 * <p><b>조회 하나뿐이다.</b> API-설계.md 2-7 이 <i>"감사 로그는 append-only다 (I-13). 생성·수정·삭제 엔드포인트를 만들지 않는다"</i>
 * 고 적었다. 기록은 관리자 행위의 부수 효과로만 쌓이므로 (도메인-모델링.md 「1. 유비쿼터스 언어」) 남기는 HTTP 경로 자체가 없다.
 *
 * <p><b>권한 판정이 이 클래스에 없다.</b> 어노테이션을 컨트롤러마다 흩뿌리지 않고 {@code /api/v1/admin/**} 전체에 한 곳으로 건다 (D-5 의
 * 지켜야 할 셋 중 둘째) — 하나를 빠뜨렸을 때 아무도 모르고, 그 하나가 비밀 댓글 본문일 수 있다. 관문 판정은 {@code SecurityConfig} 와 {@code
 * EndpointGradeTest} 의 권한 표가 지킨다.
 *
 * <p><b>{@code AuthUser} 를 받지 않는다.</b> 누가 보았는지는 남기지 않는다 — 감사 로그에 남기는 다섯에 「감사 로그 열람」이 없다. 목록에는 개인의
 * 비공개 내용이 아니라 관리자 행위의 요약만 실린다.
 */
@Tag(name = "백오피스 감사 로그", description = "관리자 행위 기록 조회")
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

  private final AuditLogQueryService auditLogQueryService;

  /** 감사 로그를 읽는다 (AD-05). */
  @Operation(summary = "감사 로그 조회", description = "최신순이다. 제재·해제·비밀 댓글 열람·블라인드·계정 파기가 남는다.")
  @GetMapping
  public AuditLogListResponse getAuditLogs(AuditLogListRequest request) {
    AuditLogSlice slice = auditLogQueryService.findAuditLogs(request.toQuery());

    return AuditLogListResponse.from(slice);
  }
}
