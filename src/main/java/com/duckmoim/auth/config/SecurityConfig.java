package com.duckmoim.auth.config;

import static com.duckmoim.auth.presentation.AuthAuthority.ADMIN;
import static com.duckmoim.auth.presentation.AuthAuthority.MACHINE;
import static com.duckmoim.auth.presentation.AuthAuthority.SIGNUP;

import com.duckmoim.auth.presentation.AuthenticationFilter;
import com.duckmoim.auth.presentation.IngestKeyFilter;
import com.duckmoim.auth.presentation.RestAccessDeniedHandler;
import com.duckmoim.auth.presentation.RestAuthenticationEntryPoint;
import com.duckmoim.auth.service.AuthenticationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

  private static final String[] INFRA = {
    "/api/health",
    "/error",
    "/v3/api-docs/**",
    "/swagger-ui/**",
    "/swagger-ui.html",
    "/api/v1/dev/token"
  };

  private static final String[] AUTH_READ = {
    "/api/v1/users/me", "/api/v1/users/nickname-availability"
  };

  private static final String[] PUBLIC_READ = {
    "/api/v1/users/*",
    "/api/v1/users/*/posts",
    "/api/v1/events",
    "/api/v1/events/*",
    "/api/v1/posts",
    "/api/v1/posts/*",
    "/api/v1/posts/*/comments"
  };

  private static final String[] SIGNUP_WRITE = {
    "/api/v1/posts/**", "/api/v1/comments/**", "/api/v1/reports"
  };

  private static final String[] PUBLIC_LOGIN = {"/api/v1/auth/kakao", "/api/v1/auth/token"};
  private static final String AUTH_TOKEN = "/api/v1/auth/token";
  private static final String SIGNUP_INFO = "/api/v1/users/me/signup-info";
  private static final String MY_PAGE = "/api/v1/users/me/**";
  private static final String ADMIN_ALL = "/api/v1/admin/**";

  // 사람이 아니라 기계가 부르는 경로 (API-설계 「2-8. 적재 (Ingest)」 · D-11).
  //
  // ADMIN_ALL 아래에 두지 않은 이유 — 저 줄은 hasAuthority(ADMIN) 하나로 백오피스
  // 전체를 덮고 있고 그것이 지켜야 하는 성질이다. 적재를 그 아래 두면 정적 키를 위한
  // 예외를 저 줄 앞에 끼워야 하는데, 경로 규칙은 순서 의존이라 나중에 순서가 바뀌면
  // 백오피스가 열린다 — 그 문 안에 비밀 댓글 본문이 있다 (CM-17).
  private static final String INGEST_ALL = IngestKeyFilter.PATH_PREFIX + "**";

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      AuthenticationService authenticationService,
      @Value("${duckmoim.ingest.key}") String ingestKey,
      RestAuthenticationEntryPoint authenticationEntryPoint,
      RestAccessDeniedHandler accessDeniedHandler)
      throws Exception {
    return http.cors(Customizer.withDefaults())
        .csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            registry -> {
              registry.requestMatchers(INFRA).permitAll();

              registry.requestMatchers(HttpMethod.POST, PUBLIC_LOGIN).permitAll();
              registry.requestMatchers(HttpMethod.DELETE, AUTH_TOKEN).authenticated();

              registry.requestMatchers(HttpMethod.GET, AUTH_READ).authenticated();
              registry.requestMatchers(HttpMethod.PUT, SIGNUP_INFO).authenticated();
              registry.requestMatchers(MY_PAGE).hasAuthority(SIGNUP);

              registry.requestMatchers(HttpMethod.GET, PUBLIC_READ).permitAll();

              registry.requestMatchers(HttpMethod.POST, SIGNUP_WRITE).hasAuthority(SIGNUP);
              registry.requestMatchers(HttpMethod.PATCH, SIGNUP_WRITE).hasAuthority(SIGNUP);
              registry.requestMatchers(HttpMethod.DELETE, SIGNUP_WRITE).hasAuthority(SIGNUP);

              registry.requestMatchers(ADMIN_ALL).hasAuthority(ADMIN);
              registry.requestMatchers(INGEST_ALL).hasAuthority(MACHINE);
              registry.anyRequest().authenticated();
            })
        .exceptionHandling(
            handling ->
                handling
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .addFilterBefore(
            new AuthenticationFilter(authenticationService),
            UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(new IngestKeyFilter(ingestKey), UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}
