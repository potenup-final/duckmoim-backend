package com.duckmoim.auth.presentation;

import com.duckmoim.auth.exception.AuthErrorCode;
import com.duckmoim.common.exception.ErrorCode;
import com.duckmoim.common.exception.ErrorResponse;
import com.duckmoim.identity.exception.UserErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

  private final ObjectMapper objectMapper;

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException {

    ErrorCode errorCode = reason();

    response.setStatus(errorCode.getStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode));
  }

  private ErrorCode reason() {
    if (signupCompleted()) {
      return AuthErrorCode.AUTH_FORBIDDEN;
    }
    return UserErrorCode.USER_SIGNUP_INFO_REQUIRED;
  }

  private boolean signupCompleted() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

    if (authentication == null) {
      return false;
    }
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .anyMatch(AuthAuthority.SIGNUP::equals);
  }
}
