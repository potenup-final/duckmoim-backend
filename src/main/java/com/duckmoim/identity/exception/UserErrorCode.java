package com.duckmoim.identity.exception;

import com.duckmoim.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {
  USER_SIGNUP_INFO_REQUIRED(HttpStatus.FORBIDDEN, "가입 정보를 먼저 입력해 주세요."),

  // 위키가 「탈퇴 포함」으로 적어 둔 코드다 (API-설계.md 「에러 코드」). 토큰 발급 경로가 회원을 못 찾을 때 쓴다 —
  // 재발급 경로는 「다시 로그인」이 맞지만 로그인 경로에는 Refresh 토큰이 없어서 그 코드가 거짓이 된다.
  USER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다.");

  private final HttpStatus status;
  private final String message;

  @Override
  public String getCode() {
    return name();
  }
}
