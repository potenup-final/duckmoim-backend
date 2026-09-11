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
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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
    "/swagger-ui.html"
  };

  // 개발용 토큰 발급 (API-설계 「2-9. 운영·개발 (요구사항에서 나오지 않은 것)」).
  //
  // INFRA 와 나란히 두지 않는다 — 저 배열은 모든 프로파일에서 열리고, 이 경로는 요청한
  // 회원번호로 admin: true 토큰까지 찍어준다. 컨트롤러의 @Profile("local") 하나가 유일한
  // 방어이던 상태라 프로파일 설정이 한 번 어긋나면 관리자 토큰 발급기가 공개된다.
  // 그 문 안에 비밀 댓글 본문이 있다 (CM-17).
  private static final String DEV_TOKEN = "/api/v1/dev/token";
  private static final String LOCAL = "local";

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

  // 조회지만 SIGNUP 이다 (CH-05 · CH-06). 가입을 마치지 않은 계정은 애초에 방 멤버가 될 수
  // 없다 — 초대 대상이 되려면 댓글을 써야 하고 댓글 작성 자체가 SIGNUP 이다. MY_PAGE 의
  // users/me/posts 와 같은 근거.
  // 메시지 목록(CH-09)이 /api/v1/chat-rooms/*/messages 라 한 칸 더 깊다. 위 둘의 * 는 한 칸만
  // 덮으므로 닿지 않고, 그러면 anyRequest().authenticated() 로 떨어져 가입 미완료 계정에게
  // 열린다 — CH-20 이 쓰기 경로에서 고친 것과 같은 모양의 구멍이다.
  private static final String[] SIGNUP_READ = {
    "/api/v1/chat-rooms", "/api/v1/chat-rooms/*", "/api/v1/chat-rooms/*/messages"
  };

  private static final String[] PUBLIC_LOGIN = {"/api/v1/auth/kakao", "/api/v1/auth/token"};
  private static final String AUTH_TOKEN = "/api/v1/auth/token";
  private static final String SIGNUP_INFO = "/api/v1/users/me/signup-info";
  private static final String MY_PAGE = "/api/v1/users/me/**";
  private static final String ADMIN_ALL = "/api/v1/admin/**";

  // 알림은 접두어 전체가 「내 것」이다 (API-설계 「2-10. 알림 (Notification) · 2차」).
  //
  // 메서드로 가르지 않고 접두어로 묶은 이유 — 남의 알림을 가리킬 수 있는 경로를 두지
  // 않기로 했고(D-14) 그래서 이 아래에 남이 부르는 엔드포인트가 생기지 않는다. 읽음
  // 처리(NT-09)도 자기 알림을 바꾸는 것이라 같은 등급이다.
  //
  // 이 줄이 깨지는 조건 — 접두어 아래에 「사람이 자기 것을 다루는」 것이 아닌 경로가 올 때다.
  // 만료 배치(NT-11a)나 워커용 경로가 생기면 MACHINE 이어야 할 것이 SIGNUP 으로 열린다.
  // 그런 경로는 반드시 이 줄 「위에」 적는다 — 아래에 적으면 이 줄이 먼저 걸린다. 메서드로
  // 가르는 것은 답이 아니다: 안 적은 메서드가 anyRequest 로 떨어져 되레 가입 미완료 유저에게
  // 열린다. EndpointGradeTest 는 손으로 유지하는 표라 새 경로를 자동으로 잡아 주지 않는다.
  //
  // 경로 자체를 두 벌 적는 것은 `/**` 가 빈 세그먼트를 먹는지가 매처 구현에 달려 있어서다.
  // 목록 경로가 조용히 anyRequest 로 떨어지면 가입 미완료 유저에게 200 이 나간다.
  private static final String[] NOTIFICATIONS = {
    "/api/v1/notifications", "/api/v1/notifications/**"
  };

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
      Environment environment,
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

              // local 에서만 등록한다. 다른 프로파일에서는 어느 규칙에도 안 걸려
              // anyRequest().authenticated() 로 떨어지고, 토큰 없는 요청은 401 이다
              // (없는 경로의 존재 여부를 비인증 요청에 알려주지 않는다 — D-13).
              if (environment.acceptsProfiles(Profiles.of(LOCAL))) {
                registry.requestMatchers(HttpMethod.POST, DEV_TOKEN).permitAll();
              }

              registry.requestMatchers(HttpMethod.POST, PUBLIC_LOGIN).permitAll();
              registry.requestMatchers(HttpMethod.DELETE, AUTH_TOKEN).authenticated();

              registry.requestMatchers(HttpMethod.GET, AUTH_READ).authenticated();
              registry.requestMatchers(HttpMethod.PUT, SIGNUP_INFO).authenticated();
              registry.requestMatchers(MY_PAGE).hasAuthority(SIGNUP);

              registry.requestMatchers(HttpMethod.GET, PUBLIC_READ).permitAll();
              registry.requestMatchers(HttpMethod.GET, SIGNUP_READ).hasAuthority(SIGNUP);

              registry.requestMatchers(HttpMethod.POST, SIGNUP_WRITE).hasAuthority(SIGNUP);
              registry.requestMatchers(HttpMethod.PATCH, SIGNUP_WRITE).hasAuthority(SIGNUP);
              registry.requestMatchers(HttpMethod.DELETE, SIGNUP_WRITE).hasAuthority(SIGNUP);

              registry.requestMatchers(NOTIFICATIONS).hasAuthority(SIGNUP);

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
