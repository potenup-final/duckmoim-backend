package com.duckmoim.safety.exception;

import com.duckmoim.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 신고 에러 코드 (API-설계.md 「4. 에러 코드」).
 *
 * <p>정본에 셋이 있는데 둘만 넣는다. {@code REPORT_ALREADY_HANDLED}(409)는 <b>재처리</b> 거부이고 그 전이가 백오피스(AD-03)
 * 소관이라, 던지는 자리가 없는 코드를 미리 만들면 어디서 쓰이는지 모르는 채로 남는다.
 *
 * <p>대상이 없을 때는 여기 코드를 쓰지 않는다. 대상별 404 가 정본에 이미 있다 — {@code USER_NOT_FOUND} · {@code POST_NOT_FOUND}
 * · {@code COMMENT_NOT_FOUND}. 신고 쪽에 같은 뜻의 코드를 또 만들면 클라이언트가 대상에 따라 다른 이름을 받게 된다.
 */
@Getter
@RequiredArgsConstructor
public enum ReportErrorCode implements ErrorCode {
  REPORT_DUPLICATED(HttpStatus.CONFLICT, "이미 신고한 대상입니다."),
  REPORT_REASON_INVALID(HttpStatus.BAD_REQUEST, "이 대상에 쓸 수 없는 사유입니다.");

  private final HttpStatus status;
  private final String message;

  @Override
  public String getCode() {
    return name();
  }
}
