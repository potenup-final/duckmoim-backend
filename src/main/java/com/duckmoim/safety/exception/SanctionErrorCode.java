package com.duckmoim.safety.exception;

import com.duckmoim.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 제재 에러 코드 (AD-04).
 *
 * <p><b>넷 다 정본에 없는 이름이다.</b> API-설계.md 「4. 에러 코드」에 제재 쪽은 {@code USER_SANCTIONED}(403) 하나뿐인데, 그것은
 * <b>제재당한 사람이 쓰기를 시도할 때</b>의 코드이고 (I-14) 여기 넷은 <b>관리자가 제재를 걸거나 풀 때</b>의 것이다. 그 표가 <i>"아래는 요구사항에서 직접
 * 나오는 것만이고, 구현하며 늘어난다"</i> 고 열어 둔 자리다.
 *
 * <p><b>{@code USER_SANCTIONED} 를 여기 두지 않는다.</b> 그것은 {@code UserErrorCode} 소속이고 (정본이 그렇게 적었다) 던지는
 * 자리도 관문이라 이 파일과 계층이 다르다.
 *
 * <p>{@code ReportErrorCode} 와 나눠 둔다. 신고와 제재는 다른 애그리게이트이고, 한 enum 에 담으면 어느 것이 어느 흐름의 코드인지 호출부에서 안
 * 보인다.
 */
@Getter
@RequiredArgsConstructor
public enum SanctionErrorCode implements ErrorCode {
  SANCTION_REASON_REQUIRED(HttpStatus.BAD_REQUEST, "제재 사유는 비울 수 없습니다."),
  SANCTION_UNTIL_MISMATCH(HttpStatus.BAD_REQUEST, "기간 정지에만 해제 시각을 정할 수 있습니다."),
  SANCTION_ALREADY_ACTIVE(HttpStatus.CONFLICT, "이미 제재 중인 회원입니다."),
  SANCTION_ALREADY_RELEASED(HttpStatus.CONFLICT, "이미 해제된 제재입니다."),
  SANCTION_NOT_FOUND(HttpStatus.NOT_FOUND, "제재를 찾을 수 없습니다.");

  private final HttpStatus status;
  private final String message;

  @Override
  public String getCode() {
    return name();
  }
}
