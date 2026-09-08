package com.duckmoim.identity.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.identity.presentation.dto.MyProfileResponse;
import com.duckmoim.identity.presentation.dto.NicknameAvailabilityResponse;
import com.duckmoim.identity.presentation.dto.SignupInfoRequest;
import com.duckmoim.identity.service.UserQueryService;
import com.duckmoim.identity.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "회원", description = "가입 정보 입력과 내 정보")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

  private final UserService userService;
  private final UserQueryService userQueryService;

  /**
   * 닉네임을 쓸 수 있는지 미리 본다 (AU-06).
   *
   * <p><b>확정이 아니다.</b> 이 답과 저장 사이에 남이 같은 닉네임을 넣을 수 있어서, 「완료」에서 409 가 날 수 있다 (API 설계 2-2). 흔한 중복을 입력
   * 중에 걸러 주는 것이 이 경로의 값이다.
   *
   * <p>등급이 {@code AUTH} 다 — <b>가입 미완료 사용자가 부르는 경로</b>라 {@code SIGNUP} 으로 닫으면 가입을 마칠 방법이 없어진다.
   */
  @Operation(summary = "닉네임 중복 확인", description = "확정이 아니다. 저장 시점에 중복이면 409 가 난다.")
  @GetMapping("/nickname-availability")
  public NicknameAvailabilityResponse checkNicknameAvailability(
      @RequestParam @NotBlank @Size(max = 20) String nickname) {

    return NicknameAvailabilityResponse.of(userQueryService.isNicknameAvailable(nickname));
  }

  /**
   * 가입 정보를 입력해 계정을 활성화한다 (AU-05).
   *
   * <p><b>한 번만 통한다.</b> 이미 입력한 사용자가 다시 부르면 409 다 — 출생연도가 가입 후 잠기기 때문이다 (API 설계 2-2). 닉네임만 바꾸는 것은
   * AU-08 의 {@code PATCH /users/me/profile} 몫이다.
   *
   * <p><b>회원번호를 본문으로 받지 않는다.</b> {@code @AuthenticationPrincipal} 에서 꺼낸다 — 남의 가입 정보를 채우는 경로를 만들지 않는
   * 유일한 장치다.
   *
   * <p><b>본문 없이 200 이다</b> (API-컨벤션.md 「Status Code 규칙」에 204 가 없다). 저장된 값은 {@code GET /users/me} 로
   * 읽는다.
   *
   * <p><b>이 호출 뒤에 토큰을 재발급해야 한다.</b> 손에 든 Access 토큰은 아직 {@code signupCompleted: false} 를 들고 있어서 쓰기가
   * 403 이다. {@code POST /api/v1/auth/token} 이 DB 를 다시 읽어 갱신된 값을 찍는다 (AU-03).
   */
  @Operation(summary = "가입 정보 입력", description = "닉네임과 출생연도. 한 번만 통한다. 이 뒤에 토큰을 재발급해야 쓰기가 열린다.")
  @PutMapping("/me/signup-info")
  public void completeSignup(
      @AuthenticationPrincipal AuthUser authUser, @Valid @RequestBody SignupInfoRequest request) {

    userService.completeSignup(request.toCommand(authUser.userId()));
  }

  /**
   * 내 정보를 읽는다.
   *
   * <p>가입 미완료 계정도 부를 수 있다 — 등급이 {@code AUTH} 다. 그때 닉네임은 {@code null} 이고 {@code signupCompleted} 가
   * {@code false} 다.
   */
  @Operation(summary = "내 정보 조회", description = "프로필 · 가입 완료 여부 · 최근 접속 구간 · 제재 상태")
  @GetMapping("/me")
  public MyProfileResponse findMyProfile(@AuthenticationPrincipal AuthUser authUser) {
    return MyProfileResponse.from(userQueryService.findMyProfile(authUser.userId()));
  }
}
