package com.duckmoim.auth.presentation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 크롤러가 보낸 적재 키를 확인한다 (D-11).
 *
 * <p><b>authority 를 부여하는 것으로 끝낸다.</b> 이 필터가 요청을 막지 않는다 — 통과 여부는 {@code SecurityConfig} 의 {@code
 * hasAuthority(MACHINE)} 이 판정한다.
 *
 * <p>그렇게 나눈 이유가 fail-closed 다. 경로를 {@code permitAll} 로 열고 여기서 막는 구조로 짜면, <b>이 필터가 등록에서 빠지거나 예외를 먹는
 * 순간 적재 경로가 인증 없이 열린다.</b> authority 방식은 반대로 실패한다 — 필터가 안 돌면 authority 가 없어 403 이다. 공개 인터넷에 열린 경로에
 * 방어선이 키 하나뿐이라 이 방향이 중요하다.
 *
 * <p>{@link AuthenticationFilter} 가 JWT 로 {@code ADMIN} 을 붙이는 것과 같은 패턴이다. 새 구조를 만들지 않는다.
 *
 * <p><b>적재 경로에서만 돈다</b> ({@link #shouldNotFilter}). 모든 요청에서 키를 보면 그 키가 API 전체의 인증 수단이 된다 — {@code
 * SecurityConfig} 의 마지막 줄이 {@code anyRequest().authenticated()} 라, {@code MACHINE} 이 붙은 요청은 등급이
 * {@code authenticated()} 인 경로를 사람 토큰 없이 통과한다. 앞으로 매처 없이 추가되는 경로도 같은 줄에 걸린다.
 */
@RequiredArgsConstructor
public class IngestKeyFilter extends OncePerRequestFilter {

  private static final String HEADER = "X-Ingest-Key";

  /**
   * 이 접두어 아래가 적재 경로다. {@code SecurityConfig} 의 등급 규칙이 이 값을 받아 쓴다 — 둘이 갈라지면 필터와 인가가 서로 다른 경로를 본다.
   */
  public static final String PATH_PREFIX = "/api/v1/ingest/";

  private final String ingestKey;

  /**
   * 적재 경로 밖에서는 아무것도 하지 않는다.
   *
   * <p>키가 맞는지도 보지 않는다 — 헤더를 읽는 것부터가 다른 경로에 {@code MACHINE} 을 붙이는 길이다.
   */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(PATH_PREFIX);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    if (matches(request.getHeader(HEADER))) {
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  HEADER, null, List.of(new SimpleGrantedAuthority(AuthAuthority.MACHINE))));
    }

    filterChain.doFilter(request, response);
  }

  /**
   * 키를 상수 시간으로 비교한다.
   *
   * <p>{@code equals} 는 처음 다른 바이트에서 빠져나와, 응답 시간이 「앞에서 몇 글자가 맞았는지」를 알려준다. 이 경로는 공개 인터넷에 있고 크롤러가 반복
   * 호출해도 이상하지 않아서, 시도 횟수로 막는 장치가 없다.
   */
  private boolean matches(String presented) {
    if (presented == null) {
      return false;
    }
    return MessageDigest.isEqual(
        presented.getBytes(StandardCharsets.UTF_8), ingestKey.getBytes(StandardCharsets.UTF_8));
  }
}
