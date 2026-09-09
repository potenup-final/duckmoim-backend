package com.duckmoim.auth.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.presentation.dto.KakaoLoginRequest;
import com.duckmoim.auth.presentation.dto.TokenRefreshRequest;
import com.duckmoim.auth.presentation.dto.TokenResponse;
import com.duckmoim.auth.service.AuthService;
import com.duckmoim.auth.service.KakaoLoginService;
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

/**
 * 인증 (AU-01 · AU-03 · AU-04).
 *
 * <p><b>매핑이 {@code /api/v1/auth} 다.</b> {@code /token} 하나만 걸려 있었는데 로그인({@code /kakao})이 붙으면서 접두어를
 * 클래스로 올렸다. 경로 문자열은 그대로다 — 컨트롤러를 둘로 나누면 같은 접두어를 두 파일이 나눠 갖게 되고, 등급 표(API 설계 2-1)와 대조하기 어려워진다.
 */
@Tag(name = "인증", description = "카카오 로그인 · 토큰 재발급 · 로그아웃")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthService authService;
  private final KakaoLoginService kakaoLoginService;

  /**
   * 카카오 인가코드로 로그인한다 (AU-01).
   *
   * <p><b>등급이 {@code PUBLIC} 이다</b> (API 설계 2-1). 로그인 전이라 토큰이 없다 — 여기가 토큰을 <b>처음</b> 받는 자리다.
   *
   * <p><b>처음 로그인이면 계정이 여기서 생긴다.</b> 그때 응답의 {@code signupCompleted} 가 {@code false} 로 나가고, 클라이언트는 그
   * 값으로 가입 화면으로 보낼지 판단한다 (API 설계 2-1: <i>"유일한 근거"</i>). 다시 로그인하면 같은 계정에 붙는다.
   *
   * <p>응답이 재발급과 같은 {@code TokenResponse} 다. 두 경로가 주는 것이 똑같아서(토큰 쌍 + 가입 완료 여부) 프론트가 같은 파서를 쓴다.
   */
  @Operation(summary = "카카오 로그인", description = "인가코드를 자체 토큰으로 바꾼다. 처음이면 계정이 가입 정보 미입력 상태로 만들어진다.")
  @PostMapping("/kakao")
  public TokenResponse loginWithKakao(@Valid @RequestBody KakaoLoginRequest request) {
    return TokenResponse.from(kakaoLoginService.login(request.code(), request.redirectUri()));
  }

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
  @PostMapping("/token")
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
  @DeleteMapping("/token")
  public void logout(@AuthenticationPrincipal AuthUser authUser) {
    authService.logout(authUser.userId());
  }
}
