package com.duckmoim.safety.exception;

import com.duckmoim.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 신고 에러 코드 (API-설계.md 「4. 에러 코드」).
 *
 * <p><b>정본의 셋을 다 쓴다.</b> STAR-55 는 {@code REPORT_ALREADY_HANDLED}(409)를 빼 두었다 — 재처리 거부의 전이가
 * 백오피스(AD-03) 소관이라 던지는 자리가 없었고, 그런 코드를 미리 만들면 어디서 쓰이는지 모르는 채로 남기 때문이다. STAR-78 이 그 자리를 만들면서 넣었다.
 *
 * <p><b>신고 대상</b>이 없을 때는 여기 코드를 쓰지 않는다. 대상별 404 가 정본에 이미 있다 — {@code USER_NOT_FOUND} · {@code
 * POST_NOT_FOUND} · {@code COMMENT_NOT_FOUND}. 신고 쪽에 같은 뜻의 코드를 또 만들면 클라이언트가 대상에 따라 다른 이름을 받게 된다.
 *
 * <p><b>409 가 둘로 갈린다.</b> {@code REPORT_ALREADY_HANDLED} 는 <i>이미 종결된</i> 건을 다시 처리하려는 것이고 (AD-03 의
 * 검증 기준), {@code REPORT_TRANSITION_NOT_ALLOWED} 는 <i>아직 종결되지 않았지만</i> 그 전이가 없는 경우다 — 되돌리기와 제자리 전이,
 * 그리고 이미 누가 잡은 건을 또 잡는 것. 하나로 합치면 아직 처리 중인 건에 「이미 처리된 신고입니다」라고 답하게 된다. {@code CommentErrorCode} 가
 * 403 을 둘로 가른 것과 같은 이유다.
 *
 * <p><b>{@code REPORT_NOT_FOUND} 와 {@code REPORT_TRANSITION_NOT_ALLOWED} 는 정본에 없는 이름이다.</b> 위 문단이
 * 말하는 것은 <i>신고당한 대상</i>이 없는 경우이고, 이것은 <i>신고 건 자체</i>가 없는 경우다 — 백오피스가 없는 {@code reportId} 를 처리하려 할
 * 때다 (AD-03). 에러 표가 <i>"구현하며 늘어난다"</i> 고 열어 둔 자리다.
 */
@Getter
@RequiredArgsConstructor
public enum ReportErrorCode implements ErrorCode {
  REPORT_DUPLICATED(HttpStatus.CONFLICT, "이미 신고한 대상입니다."),
  REPORT_REASON_INVALID(HttpStatus.BAD_REQUEST, "이 대상에 쓸 수 없는 사유입니다."),
  REPORT_ALREADY_HANDLED(HttpStatus.CONFLICT, "이미 처리된 신고입니다."),
  REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "신고를 찾을 수 없습니다."),
  REPORT_TRANSITION_NOT_ALLOWED(HttpStatus.CONFLICT, "지금 상태에서 할 수 없는 처리입니다.");

  private final HttpStatus status;
  private final String message;

  @Override
  public String getCode() {
    return name();
  }
}
