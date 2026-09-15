package com.duckmoim.identity.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.identity.service.AdminUserPurgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 백오피스가 회원 계정에 하는 조치 (AD-05).
 *
 * <p>제재(AD-04)는 계정을 <b>막는</b> 일이라 safety 가 가져가고, 여기는 계정을 <b>지우는</b> 일이라 identity 다. 경로가 {@code
 * /admin/users/{userId}} 로 겹치지만 여는 애그리게이트가 다르다.
 *
 * <p><b>권한 판정이 이 클래스에 없다.</b> API-설계.md 「2-7. 백오피스 (Admin)」가 <i>"검증은 인터셉터 한 곳에서 {@code
 * /api/v1/admin/**} 전체에 건다"</i> 고 정했다. 관문 판정은 {@code SecurityConfig} 와 {@code EndpointGradeTest} 의
 * 권한 표가 지킨다.
 *
 * <p>{@code AuthUser} 를 받는 것은 인가가 아니라 <b>감사 로그의 행위자</b> 때문이다.
 */
@Tag(name = "백오피스 계정", description = "계정 파기")
@RestController
@RequestMapping("/api/v1/admin/users/{userId}")
@RequiredArgsConstructor
public class AdminUserController {

  private final AdminUserPurgeService adminUserPurgeService;

  /**
   * 계정을 파기한다 (AD-05).
   *
   * <p><b>탈퇴가 아니다.</b> 탈퇴(AU-11)는 본인이 하고 닉네임 · 프로필 이미지만 비운다. 파기는 개인정보 컬럼을 비우고 감사 로그에 {@code PURGE}
   * 를 남긴다.
   *
   * <p><b>응답도 없이 200 이다.</b> API-컨벤션.md 「Status Code 규칙」이 명령 성공을 200 으로 정했고, 결과가 「파기됐다」 하나로 정해져 있어
   * 돌려줄 정보가 없다. 해제 · 블라인드가 같은 모양이다.
   *
   * <p><b>{@code DELETE} 가 아니다.</b> 행을 지우지 않고 (작성자 블록이 내부 조인으로 읽는다) 사유를 본문으로 받아야 한다. 블라인드가 상태 전이인데도
   * {@code POST .../blind} 인 것과 같은 자리다.
   */
  @Operation(summary = "계정 파기", description = "개인정보 컬럼을 비우고 PURGE 감사 로그를 남긴다. 되돌릴 수 없다.")
  @PostMapping("/purge")
  public void purge(
      @PathVariable Long userId,
      @AuthenticationPrincipal AuthUser authUser,
      @Valid @RequestBody AdminUserPurgeRequest request) {

    adminUserPurgeService.purge(userId, authUser.userId(), request.reason());
  }
}
