package com.duckmoim.identity.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.identity.presentation.dto.MyProfileResponse;
import com.duckmoim.identity.presentation.dto.NicknameAvailabilityResponse;
import com.duckmoim.identity.presentation.dto.ProfileUpdateRequest;
import com.duckmoim.identity.presentation.dto.PublicProfileResponse;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "회원", description = "가입 정보 입력 · 내 정보 · 프로필 수정 · 공개 프로필")
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

  /**
   * 닉네임과 한줄소개를 고친다 (AU-08).
   *
   * <p><b>부분 수정이다.</b> 안 보낸 필드는 바뀌지 않고, 한줄소개는 빈 문자열로 비운다. 닉네임은 비울 수 없다 — 규칙과 근거는 {@link
   * ProfileUpdateRequest} 에 있다.
   *
   * <p><b>출생연도를 받지 않는다.</b> 가입 후 잠긴다 (API 설계 2-2). 요청 DTO 에 필드가 없어서 보내도 무시된다.
   *
   * <p>등급이 {@code SIGNUP} 이다 — 가입 미완료 계정의 수정 경로는 {@code PUT /me/signup-info} 하나다 (AU-07).
   *
   * <p><b>본문 없이 200 이다</b> (API 컨벤션 「Status Code 규칙」에 204 가 없다). 바뀐 값은 {@code GET /users/me} 나 공개
   * 프로필로 읽는다.
   */
  @Operation(summary = "프로필 수정", description = "닉네임과 한줄소개. 안 보낸 필드는 바뀌지 않는다. 출생연도는 받지 않는다.")
  @PatchMapping("/me/profile")
  public void updateProfile(
      @AuthenticationPrincipal AuthUser authUser,
      @Valid @RequestBody ProfileUpdateRequest request) {

    userService.updateProfile(request.toCommand(authUser.userId()));
  }

  /**
   * 남의 프로필을 읽는다 (AU-09).
   *
   * <p><b>등급이 {@code PUBLIC} 이다.</b> 비회원이 부른다 — 만나기 전에 상대를 확인하는 화면이라 로그인을 요구하면 그 확인이 막힌다.
   *
   * <p><b>이 매핑이 {@code /me} 계열보다 넓다.</b> {@code /users/me} 와 {@code /users/nickname-availability} 도
   * 이 패턴에 걸리는 모양인데, 스프링이 <b>리터럴 경로를 변수 경로보다 먼저</b> 고르므로 그쪽으로 간다. 등급이 다르므로 ({@code AUTH} vs {@code
   * PUBLIC}) 그 우선순위가 뒤집히면 남의 정보가 열리는 것이 아니라 <b>내 정보 조회가 숫자 변환에서 터진다.</b> 조용히 뒤집히지 않게 테스트로 못박아 두었다.
   *
   * <p>모집글은 담지 않는다 — API 설계가 <i>"건수가 늘면 프로필 조회가 같이 무거워진다"</i> 로 갈라 놨다. {@code
   * /users/&#123;userId&#125;/posts} 는 G 티켓이다.
   */
  @Operation(
      summary = "공개 프로필 조회",
      description = "닉네임 · 프로필 이미지 · 한줄소개 · 최근 접속 구간. 탈퇴하거나 가입을 마치지 않은 회원은 404 다.")
  @GetMapping("/{userId}")
  public PublicProfileResponse findPublicProfile(@PathVariable Long userId) {
    return PublicProfileResponse.from(userQueryService.findPublicProfile(userId));
  }
}
