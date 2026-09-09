package com.duckmoim.identity.presentation.dto;

import com.duckmoim.identity.service.SignupCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 가입 정보 입력 요청 (AU-05).
 *
 * <p>모양이 위키에 없어서 정했다. 필드명은 도메인 식별자를 그대로 쓴다 — {@code SignupInfo} 가 닉네임과 출생연도를 갖는다 (API-컨벤션.md 「필드 표기
 * 규칙」).
 *
 * <p><b>형식만 본다.</b> 만 14세 판정(I-15)과 닉네임 중복(I-01)은 도메인과 제약의 몫이다. 길이 20 은 {@code uk_user_nickname} 이
 * 걸린 컬럼 폭(V10)이라 여기서 막지 않으면 저장에서 잘린다.
 *
 * <p><b>{@code birthYear} 의 아래쪽 경계를 두지 않았다.</b> {@code @Positive} 로 연도가 아닌 값만 막는다 — 위키가 하한을 정하지 않았고
 * 출생연도는 어느 응답에도 나가지 않는다.
 */
public record SignupInfoRequest(
    @NotBlank(message = "닉네임은 필수입니다.") @Size(max = 20, message = "닉네임은 20자 이내여야 합니다.")
        String nickname,
    @NotNull(message = "출생연도는 필수입니다.") @Positive(message = "출생연도가 올바르지 않습니다.") Integer birthYear) {

  public SignupCommand toCommand(Long userId) {
    return new SignupCommand(userId, nickname, birthYear);
  }
}
