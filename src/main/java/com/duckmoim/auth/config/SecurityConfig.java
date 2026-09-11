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

  // 여기 두 actuator 경로는 사람이 아니라 인프라가 부른다 — ALB 대상 그룹의 상태 검사와
  // 각 EC2 의 Alloy 다. 둘 다 토큰을 들고 다니지 않으므로 인가를 요구하면 상태 검사가
  // 401 을 받고 인스턴스가 통째로 로테이션에서 빠진다.
  //
  // 열어도 되는 이유는 노출 자체가 좁기 때문이다 (application.yml 의
  // management.endpoints.web.exposure.include). 여기 이름을 더하기 전에 그 목록을 먼저 본다 —
  // /actuator/env 를 노출해 두고 이 배열에 더하면 DB 비밀번호가 인터넷에 나간다.
  private static final String[] INFRA = {
    "/api/health",
    "/actuator/health",
    "/actuator/prometheus",
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

  // /api/v1/chat-rooms/** 는 메시지 전송(CH-07)이다. 조회(CH-05 · CH-06)는 GET 이라 이 줄이
  // 덮지 않고, 그쪽은 같은 SIGNUP 이되 별도로 걸린다 — 여기 GET 을 섞으면 앞으로 열리는
  // 채팅 조회 경로가 이 배열의 ** 아래로 조용히 들어온다.
  private static final String[] SIGNUP_WRITE = {
    "/api/v1/posts/**", "/api/v1/comments/**", "/api/v1/reports", "/api/v1/chat-rooms/**"
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
