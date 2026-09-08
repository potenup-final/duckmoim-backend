package com.duckmoim.identity.exception;

import com.duckmoim.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {
  USER_SIGNUP_INFO_REQUIRED(HttpStatus.FORBIDDEN, "가입 정보를 먼저 입력해 주세요."),
  USER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."),

  // 문구를 나이가 아니라 연도로 쓴다 (API-설계.md 「에러 코드」). 판정이 「올해 − 출생연도 >= 15」라
  // 경계 연도에 태어난 만 14세도 가입할 수 있어서, "만 14세 미만은 가입할 수 없습니다" 는 그 사람에게
  // 사실과 다르다.
  USER_UNDER_MINIMUM_AGE(HttpStatus.BAD_REQUEST, "만 15세가 되는 해부터 가입할 수 있습니다.");

  private final HttpStatus status;
  private final String message;

  @Override
  public String getCode() {
    return name();
  }
}
