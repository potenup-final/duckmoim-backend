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
  USER_UNDER_MINIMUM_AGE(HttpStatus.BAD_REQUEST, "만 15세가 되는 해부터 가입할 수 있습니다."),

  // 가입 정보 입력은 한 번뿐이다 (API-설계.md 2-2). 출생연도가 가입 후 잠기기 때문이다 —
  // 닉네임만 바꾸는 것은 AU-08 의 PATCH /users/me/profile 몫이다.
  USER_SIGNUP_INFO_ALREADY_SET(HttpStatus.CONFLICT, "가입 정보는 이미 입력되었습니다."),

  // I-01 닉네임 유일성. 도메인 3.3 이 「DB 유니크 제약. 위반을 409로 변환」으로 정했다.
  // 가입(AU-06)과 프로필 수정(AU-08) 양쪽이 쓴다.
  USER_NICKNAME_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),

  /**
   * 제재 중인 회원이 쓰기를 시도했다 (I-14).
   *
   * <p><b>여기 적힌 문구는 사유를 모를 때의 것이다.</b> API-설계.md 「4. 에러 코드」가 <i>"{@code USER_SANCTIONED} 의 {@code
   * message} 에 제재 사유를 담는다"</i> 고 정했고, 실제 응답에는 그 회원에게 걸린 사유가 실린다 (AD-04 · AU-12).
   *
   * <p><b>정본에 있는데 코드에 없던 값이다.</b> 던질 자리가 AD-04 소관이라 STAR-80 이 만들면서 넣었다.
   */
  USER_SANCTIONED(HttpStatus.FORBIDDEN, "제재 중에는 글을 쓸 수 없습니다.");

  private final HttpStatus status;
  private final String message;

  @Override
  public String getCode() {
    return name();
  }
}
