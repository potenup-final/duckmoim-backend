package com.duckmoim.auth.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.presentation.dto.TokenRefreshRequest;
import com.duckmoim.auth.presentation.dto.TokenResponse;
import com.duckmoim.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증", description = "토큰 재발급과 로그아웃")
@RestController
@RequestMapping("/api/v1/auth/token")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;

  /**
   * Refresh 토큰으로 새 토큰 쌍을 받는다 (AU-03).
   *
   * <p><b>등급이 {@code PUBLIC} 이다</b> (API-설계.md 2-1). Access 가 이미 만료됐을 때 부르는 경로라 Access 를 요구할 수 없다.
   * 인증은 본문의 Refresh 토큰 자신이 한다.
   *
   * <p>재사용이면 401 이 나가고 <b>그 회원의 세션이 전부 폐기된다.</b> 판정과 폐기는 service 가 한 트랜잭션에서 한다.
   */
  @Operation(
      summary = "토큰 재발급",
      description = "쓰인 리프레시 토큰은 즉시 폐기된다. 재사용하면 401 과 함께 그 회원의 모든 세션이 끊긴다.")
  @PostMapping
  public TokenResponse refresh(@Valid @RequestBody TokenRefreshRequest request) {
    return TokenResponse.from(authService.refresh(request.refreshToken()));
  }

  /**
   * 로그아웃한다 (AU-04).
   *
   * <p><b>Refresh 토큰을 받지 않는다.</b> 등급이 {@code AUTH} 라 요청자가 Access 로 이미 증명됐고, 지우는 것은 그 회원의 <b>모든</b>
   * 기기 토큰이라 어느 한 장을 지목할 이유가 없다.
   *
   * <p>{@code authUser} 는 null 이 될 수 없다 — 이 경로가 {@code AUTH} 등급이라 익명 요청은 관문에서 401 로 끝난다.
   *
   * <p>성공은 200 이다. 컨벤션의 상태 코드 표에 204 가 없다 (API-컨벤션.md 「Status Code 규칙」).
   */
  @Operation(summary = "로그아웃", description = "그 회원의 리프레시 토큰을 전부 지우고, 이미 발급된 액세스 토큰도 즉시 무효화한다.")
  @DeleteMapping
  public void logout(@AuthenticationPrincipal AuthUser authUser) {
    authService.logout(authUser.userId());
  }
}
