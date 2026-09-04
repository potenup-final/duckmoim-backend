package com.duckmoim.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 에러 코드 계약. 응답 봉투에 나가는 {@code code}·{@code message} 와 HTTP 상태를 한 곳에 묶는다.
 *
 * <h2>구현체를 어디에 두는가</h2>
 *
 * <ul>
 *   <li><b>공통</b> — {@link CommonErrorCode}. 도메인과 무관한 프로토콜 수준의 실패만 담는다. 잘못된 요청 형식, 없는 경로, 서버
 *       오류처럼 {@link GlobalExceptionHandler} 가 <b>직접 이름을 부르는</b> 코드다.
 *   <li><b>도메인</b> — {@code com.duckmoim.{도메인}.exception.{도메인}ErrorCode}. 예: {@code
 *       post.exception.PostErrorCode}, {@code member.exception.MemberErrorCode}. 그 도메인의 규칙 위반만
 *       담는다.
 * </ul>
 *
 * <h2>지켜야 할 두 가지</h2>
 *
 * <ol>
 *   <li><b>핸들러는 도메인 코드를 import 하지 않는다.</b> 도메인 코드는 오직 {@link BusinessException} 에 실려 들어온다.
 *       그래야 {@code common} 이 도메인에 의존하지 않는다. 새 도메인이 생겨도 핸들러는 그대로다.
 *   <li><b>코드 이름에 도메인을 넣는다.</b> {@code getCode()} 가 {@code name()} 을 그대로 쓰므로, 서로 다른 enum 이 같은
 *       이름을 쓰면 클라이언트에서 구분되지 않는다. {@code NOT_FOUND} 가 아니라 {@code POST_NOT_FOUND} ·
 *       {@code MEMBER_NOT_FOUND} 로 적는다.
 * </ol>
 *
 * <h2>도메인 구현 예</h2>
 *
 * <pre>{@code
 * @Getter
 * @RequiredArgsConstructor
 * public enum PostErrorCode implements ErrorCode {
 *   POST_NOT_FOUND(HttpStatus.NOT_FOUND, "모집글을 찾을 수 없습니다."),
 *   POST_ALREADY_CLOSED(HttpStatus.CONFLICT, "이미 마감된 모집글입니다.");
 *
 *   private final HttpStatus status;
 *   private final String message;
 *
 *   @Override
 *   public String getCode() {
 *     return name();
 *   }
 * }
 * }</pre>
 *
 * 서비스에서는 {@code throw new BusinessException(PostErrorCode.POST_ALREADY_CLOSED)} 한 줄이면 된다.
 * {@code try-catch} 로 감싸거나 {@code ResponseEntity} 를 직접 만들지 않는다.
 */
public interface ErrorCode {

  String getCode();

  String getMessage();

  HttpStatus getStatus();
}
