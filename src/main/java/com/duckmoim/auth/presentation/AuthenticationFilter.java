package com.duckmoim.auth.presentation;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.common.exception.BusinessException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
public class AuthenticationFilter extends OncePerRequestFilter {

  public static final String ERROR_CODE = "authErrorCode";

  private static final String HEADER = "Authorization";
  private static final String PREFIX = "Bearer ";

  private final TokenProvider tokenProvider;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String accessToken = resolveAccessToken(request);

    if (accessToken != null) {
      try {
        AuthUser authUser = tokenProvider.readAccessToken(accessToken);
        SecurityContextHolder.getContext().setAuthentication(authenticationOf(authUser));
      } catch (BusinessException e) {
        request.setAttribute(ERROR_CODE, e.getErrorCode());
      }
    }

    filterChain.doFilter(request, response);
  }

  private String resolveAccessToken(HttpServletRequest request) {
    String header = request.getHeader(HEADER);

    if (header == null || !header.startsWith(PREFIX)) {
      return null;
    }
    return header.substring(PREFIX.length());
  }

  private UsernamePasswordAuthenticationToken authenticationOf(AuthUser authUser) {
    List<GrantedAuthority> authorities = new ArrayList<>();

    if (authUser.signupCompleted()) {
      authorities.add(new SimpleGrantedAuthority(AuthAuthority.SIGNUP));
    }
    if (authUser.admin()) {
      authorities.add(new SimpleGrantedAuthority(AuthAuthority.ADMIN));
    }
    return new UsernamePasswordAuthenticationToken(authUser, null, authorities);
  }
}
