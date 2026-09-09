package com.duckmoim.catalog.exception;

import com.duckmoim.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 행사 에러 코드 (API-설계.md 「4. 에러 코드」).
 *
 * <p>정본 표에 행사 것이 없다. 그 표가 「요구사항에서 직접 나오는 것만이고, 구현하며 늘어난다」 라고 적어둔 자리라, 상세 조회(EV-07)가 처음으로 필요로 하는 코드를
 * 여기서 만든다. 이름은 {@code {도메인}_{상황}} 규칙을 따른다.
 *
 * <p><b>domain 이 아니라 여기 산다.</b> {@code ErrorCode} 가 {@code HttpStatus} 를 들고 있어 domain 에 두면 domain 이
 * 프레임워크에 의존하지 않는다는 규칙과 부딪힌다. {@code CommentErrorCode} 와 같은 배치다.
 */
@Getter
@RequiredArgsConstructor
public enum EventErrorCode implements ErrorCode {
  EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "행사를 찾을 수 없습니다."),
  EVENT_REGION_UNKNOWN(HttpStatus.BAD_REQUEST, "등록되지 않은 지역 코드입니다.");

  private final HttpStatus status;
  private final String message;

  @Override
  public String getCode() {
    return name();
  }
}
