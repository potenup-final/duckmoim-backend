package com.duckmoim.notification.exception;

import com.duckmoim.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 알림 에러 코드 (API-설계.md 「4. 에러 코드」).
 *
 * <p><b>{@code PUSH_ENDPOINT_NOT_ALLOWED} 는 방어다.</b> 구독 주소는 브라우저가 주는 값이지만 요청 본문으로 들어오므로 <b>사람이 아무
 * 주소나 적어 보낼 수 있다</b>. 그대로 두면 서버가 그 주소로 요청을 보낸다 (PR #157 리뷰).
 *
 * <p><b>코드가 하나뿐이었다.</b> 알림은 지금까지 읽기만 있어 실패할 자리가 없었고, 이 티켓이 처음으로 「대상을 가리키는」 요청을 받는다 (NT-09). 나머지
 * 요구사항의 코드는 그 티켓이 더한다 — 미리 지어내면 쓰지 않는 상수가 남고 그것이 다음 담당의 기준선이 된다 ({@code ChatErrorCode} 와 같은 판단이다).
 *
 * <p><b>남의 알림도 이 코드다.</b> 403 이면 「그 번호의 알림이 존재한다」를 알려주게 되어, 1장의 「리소스의 존재 자체를 숨겨야 하면 403 이 아니라 404」와
 * 부딪힌다 (API-설계.md 「5. 결정 사항」 D-14 ②). 그래서 남의 것과 없는 것이 <b>구별되지 않게</b> 같은 응답으로 나간다.
 *
 * <p><b>domain 이 아니라 여기 산다.</b> {@code ErrorCode} 가 {@code HttpStatus} 를 들고 있어 domain 에 두면 domain 이
 * Spring 에 의존하지 않는다는 규칙과 부딪힌다.
 */
@Getter
@RequiredArgsConstructor
public enum NotificationErrorCode implements ErrorCode {
  NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),
  PUSH_ENDPOINT_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "푸시 구독 주소가 올바르지 않습니다.");

  private final HttpStatus status;
  private final String message;

  @Override
  public String getCode() {
    return name();
  }
}
