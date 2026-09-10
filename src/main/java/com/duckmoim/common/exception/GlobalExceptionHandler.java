package com.duckmoim.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  /**
   * 문구는 예외가 들고 온 것을 쓴다.
   *
   * <p>생성자 하나짜리로 만든 예외는 그 값이 곧 코드의 고정 문구라 동작이 그대로이고, 문서가 요청마다 다른 문장을 요구한 자리(예: {@code
   * USER_SANCTIONED} 의 제재 사유)만 갈린다.
   */
  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
    return respond(e, e.getErrorCode(), e.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidationException(
      MethodArgumentNotValidException e) {

    String message =
        e.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(DefaultMessageSourceResolvable::getDefaultMessage)
            .orElse(CommonErrorCode.INVALID_INPUT.getMessage());

    return ResponseEntity.badRequest()
        .body(new ErrorResponse(CommonErrorCode.INVALID_INPUT.getCode(), message));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
    return respond(e, CommonErrorCode.INVALID_INPUT);
  }

  /**
   * 경로 변수나 쿼리 파라미터를 선언한 타입으로 바꿀 수 없을 때다 — {@code /api/v1/posts/abc/comments} 처럼.
   *
   * <p>나열하지 않으면 캐치올로 떨어져 <b>500</b> 이 나간다. 클라이언트가 잘못 부른 것을 서버 장애로 알려주는 셈이고, 5xx 라 로그도 ERROR 로 쌓인다.
   *
   * <p>메시지는 어느 파라미터가 틀렸는지 적지 않고 공통 문구를 쓴다. {@code handleNotReadable} 과 같은 판단이다 — 예외 메시지에 변환기와 대상 타입
   * 이름이 들어 있어서, 그것을 그대로 흘리면 내부 사정이 응답에 나간다.
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
    return respond(e, CommonErrorCode.INVALID_INPUT);
  }

  /**
   * 쿼리 파라미터나 경로 변수에 붙은 제약이 깨질 때다 — {@code ?nickname=} 이 빈 값인 경우처럼.
   *
   * <p><b>{@code @RequestBody} 와 예외 타입이 다르다.</b> 본문은 {@link MethodArgumentNotValidException} 이고 메서드
   * 파라미터는 이쪽이다. 나열하지 않으면 캐치올로 떨어져 <b>500</b> 이 나간다 — {@code handleTypeMismatch} 와 같은 판단이다. 실측했다.
   *
   * <p>제약 메시지를 그대로 쓴다. 그 문구는 우리가 DTO 와 파라미터에 직접 적은 것이라 내부 사정이 새지 않는다.
   */
  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<ErrorResponse> handleParameterValidation(
      HandlerMethodValidationException e) {

    String message =
        e.getAllValidationResults().stream()
            .flatMap(result -> result.getResolvableErrors().stream())
            .findFirst()
            .map(MessageSourceResolvable::getDefaultMessage)
            .orElse(CommonErrorCode.INVALID_INPUT.getMessage());

    return ResponseEntity.badRequest()
        .body(new ErrorResponse(CommonErrorCode.INVALID_INPUT.getCode(), message));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException e) {
    return respond(e, CommonErrorCode.ENDPOINT_NOT_FOUND);
  }

  /**
   * 필수 쿼리 파라미터가 없을 때다 — {@code ?nickname=} 처럼 빈 값으로 온 것과 다르다. 빈 값은 {@link
   * HandlerMethodValidationException} 이 잡는다.
   *
   * <p>나열하지 않으면 캐치올로 떨어져 <b>500</b> 이 나간다. 파라미터 이름 · 타입이 담긴 예외 메시지를 그대로 쓰지 않고 공통 문구를 쓴다 — {@code
   * handleNotReadable} 과 같은 판단이다.
   */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ErrorResponse> handleMissingParameter(
      MissingServletRequestParameterException e) {
    return respond(e, CommonErrorCode.INVALID_INPUT);
  }

  /** 매핑에 없는 HTTP 메서드로 부를 때다 — 나열하지 않으면 캐치올로 떨어져 <b>500</b> 이 나간다. */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ErrorResponse> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException e) {
    return respond(e, CommonErrorCode.METHOD_NOT_ALLOWED);
  }

  /** 지원하지 않는 {@code Content-Type} 으로 부를 때다 — 나열하지 않으면 캐치올로 떨어져 <b>500</b> 이 나간다. */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(
      HttpMediaTypeNotSupportedException e) {
    return respond(e, CommonErrorCode.UNSUPPORTED_MEDIA_TYPE);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleException(Exception e) {
    return respond(e, CommonErrorCode.INTERNAL_ERROR);
  }

  private ResponseEntity<ErrorResponse> respond(Exception e, ErrorCode errorCode) {
    return respond(e, errorCode, errorCode.getMessage());
  }

  private ResponseEntity<ErrorResponse> respond(Exception e, ErrorCode errorCode, String message) {

    HttpStatus status = errorCode.getStatus();

    if (status.is5xxServerError()) {
      log.error(
          "[GlobalExceptionHandler.respond] Request failed. status={}, code={}",
          status.value(),
          errorCode.getCode(),
          e);
    } else {
      log.warn(
          "[GlobalExceptionHandler.respond] Request rejected. status={}, code={}, exception={}",
          status.value(),
          errorCode.getCode(),
          e.getClass().getSimpleName());
    }

    return ResponseEntity.status(status).body(new ErrorResponse(errorCode.getCode(), message));
  }
}
