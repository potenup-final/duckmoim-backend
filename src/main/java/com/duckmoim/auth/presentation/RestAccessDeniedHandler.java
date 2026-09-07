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
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorityAuthorizationDecision;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.GrantedAuthority;
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

    ErrorCode errorCode = reason(accessDeniedException);

    response.setStatus(errorCode.getStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode));
  }

  /**
   * <b>「무슨 권한이 필요했는가」로 사유를 정한다.</b> 「이 사람이 가입을 마쳤는가」로 정하면, 가입 미완료 계정이 관리자 경로에서 막혔을 때 「가입 정보를
   * 입력하세요」가 나간다 — 가입을 마쳐도 관리자가 되지 않으므로 사용자가 빠져나올 수 없는 안내다.
   */
  private ErrorCode reason(AccessDeniedException accessDeniedException) {
    if (requiredAuthorities(accessDeniedException).contains(AuthAuthority.SIGNUP)) {
      return UserErrorCode.USER_SIGNUP_INFO_REQUIRED;
    }
    return AuthErrorCode.AUTH_FORBIDDEN;
  }

  private List<String> requiredAuthorities(AccessDeniedException accessDeniedException) {
    if (accessDeniedException instanceof AuthorizationDeniedException denied
        && denied.getAuthorizationResult() instanceof AuthorityAuthorizationDecision decision) {
      return decision.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }
    return List.of();
  }
}
