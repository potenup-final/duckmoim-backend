package com.duckmoim.auth.presentation;

import com.duckmoim.auth.domain.TokenProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

  private static final String[] DOCS = {
    "/api/health", "/error", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
  };

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      TokenProvider tokenProvider,
      RestAuthenticationEntryPoint authenticationEntryPoint,
      RestAccessDeniedHandler accessDeniedHandler)
      throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            request ->
                request
                    .requestMatchers(DOCS)
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/kakao", "/api/v1/auth/token")
                    .permitAll()
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/auth/token")
                    .authenticated()
                    .requestMatchers(
                        HttpMethod.GET, "/api/v1/users/me", "/api/v1/users/nickname-availability")
                    .authenticated()
                    .requestMatchers(HttpMethod.PUT, "/api/v1/users/me/signup-info")
                    .authenticated()
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/users/me/profile")
                    .hasAuthority(AuthAuthority.SIGNUP)
                    .requestMatchers(HttpMethod.POST, "/api/v1/users/me/profile-image")
                    .hasAuthority(AuthAuthority.SIGNUP)
                    .requestMatchers(
                        HttpMethod.GET, "/api/v1/users/me/posts", "/api/v1/users/me/comments")
                    .hasAuthority(AuthAuthority.SIGNUP)
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/users/me")
                    .hasAuthority(AuthAuthority.SIGNUP)
                    .requestMatchers(
                        HttpMethod.GET, "/api/v1/users/{userId}", "/api/v1/users/{userId}/posts")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/events", "/api/v1/events/{eventId}")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/posts", "/api/v1/posts/{postId}")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/posts")
                    .hasAuthority(AuthAuthority.SIGNUP)
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/posts/{postId}")
                    .hasAuthority(AuthAuthority.SIGNUP)
                    .requestMatchers(HttpMethod.POST, "/api/v1/posts/{postId}/close")
                    .hasAuthority(AuthAuthority.SIGNUP)
                    .requestMatchers(HttpMethod.GET, "/api/v1/posts/{postId}/comments")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/posts/{postId}/comments")
                    .hasAuthority(AuthAuthority.SIGNUP)
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/comments/{commentId}")
                    .hasAuthority(AuthAuthority.SIGNUP)
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/comments/{commentId}")
                    .hasAuthority(AuthAuthority.SIGNUP)
                    .requestMatchers(HttpMethod.POST, "/api/v1/reports")
                    .hasAuthority(AuthAuthority.SIGNUP)
                    .requestMatchers("/api/v1/admin/**")
                    .hasAuthority(AuthAuthority.ADMIN)
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            handling ->
                handling
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .addFilterBefore(
            new AuthenticationFilter(tokenProvider), UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}
