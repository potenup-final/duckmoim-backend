package com.duckmoim.auth.presentation;

import com.duckmoim.auth.exception.AuthErrorCode;
import com.duckmoim.common.exception.ErrorCode;
import com.duckmoim.common.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authenticationException)
      throws IOException {

    ErrorCode errorCode = reasonOf(request);

    response.setStatus(errorCode.getStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode));
  }

  private ErrorCode reasonOf(HttpServletRequest request) {
    Object reason = request.getAttribute(AuthenticationFilter.ERROR_CODE);

    if (reason instanceof ErrorCode errorCode) {
      return errorCode;
    }
    return AuthErrorCode.AUTH_ACCESS_TOKEN_INVALID;
  }
}
