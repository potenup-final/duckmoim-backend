package com.duckmoim.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final String UNKNOWN_PATH = "unknown";

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<Object> handleBusinessException(BusinessException ex, WebRequest request) {
    ErrorCode errorCode = ex.getErrorCode();
    return handleExceptionInternal(
        ex, ErrorResponse.of(errorCode), new HttpHeaders(), errorCode.getStatus(), request);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> handleUnexpectedException(Exception ex, WebRequest request) {
    return handleExceptionInternal(
        ex, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {

    ErrorResponse response =
        body instanceof ErrorResponse envelope
            ? envelope
            : ErrorResponse.of(resolveErrorCode(statusCode));

    if (statusCode.is5xxServerError()) {
      log.error(
          "[GlobalExceptionHandler.handleExceptionInternal] Request failed. code={}, path={}",
          response.code(),
          resolvePath(request),
          ex);
    } else {
      log.warn(
          "[GlobalExceptionHandler.handleExceptionInternal] Request rejected. code={}, status={},"
              + " path={}, exception={}",
          response.code(),
          statusCode.value(),
          resolvePath(request),
          ex.getClass().getSimpleName());
    }

    return super.handleExceptionInternal(ex, response, headers, statusCode, request);
  }

  private ErrorCode resolveErrorCode(HttpStatusCode statusCode) {
    if (HttpStatus.NOT_FOUND.isSameCodeAs(statusCode)) {
      return CommonErrorCode.ENDPOINT_NOT_FOUND;
    }
    if (HttpStatus.METHOD_NOT_ALLOWED.isSameCodeAs(statusCode)) {
      return CommonErrorCode.METHOD_NOT_ALLOWED;
    }
    if (HttpStatus.UNSUPPORTED_MEDIA_TYPE.isSameCodeAs(statusCode)) {
      return CommonErrorCode.UNSUPPORTED_MEDIA_TYPE;
    }
    if (HttpStatus.NOT_ACCEPTABLE.isSameCodeAs(statusCode)) {
      return CommonErrorCode.NOT_ACCEPTABLE;
    }
    if (statusCode.is5xxServerError()) {
      return CommonErrorCode.INTERNAL_ERROR;
    }
    return CommonErrorCode.INVALID_INPUT;
  }

  private String resolvePath(WebRequest request) {
    if (request instanceof ServletWebRequest servletRequest) {
      return servletRequest.getRequest().getRequestURI();
    }
    return UNKNOWN_PATH;
  }
}
