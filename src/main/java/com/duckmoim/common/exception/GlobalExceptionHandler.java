package com.duckmoim.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
    return respond(e, e.getErrorCode());
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

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException e) {
    return respond(e, CommonErrorCode.ENDPOINT_NOT_FOUND);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleException(Exception e) {
    return respond(e, CommonErrorCode.INTERNAL_ERROR);
  }

  private ResponseEntity<ErrorResponse> respond(Exception e, ErrorCode errorCode) {
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

    return ResponseEntity.status(status).body(ErrorResponse.of(errorCode));
  }
}
