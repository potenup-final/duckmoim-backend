package com.duckmoim.auth.presentation;

import com.duckmoim.auth.config.SecurityConfig;
import com.duckmoim.auth.infra.JwtProvider;
import com.duckmoim.auth.service.AuthenticationService;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * 컨트롤러 슬라이스에 우리 보안 설정을 가져온다.
 *
 * <p>{@code @WebMvcTest} 는 {@code @Controller} 계열만 스캔하고 {@code @Configuration} 은 집지 않는다. 그래서 이것 없이는
 * Spring Boot 기본 보안이 걸려 <b>공개 경로까지 401</b> 이 된다. 필터를 끄는 방법({@code addFilters = false})은 쓰지 않는다 —
 * 컨트롤러 슬라이스가 봐야 하는 것에 인증·인가가 들어 있어서, 끄면 그 검증이 사라진다.
 *
 * <p>{@link JwtProvider} 까지 가져오므로 슬라이스에서도 진짜 토큰을 발급해 헤더에 붙일 수 있다.
 *
 * <p>{@link AuthenticationService} 는 회원을 읽어 무효화 시각을 보는데(AU-04) 슬라이스에는 DB 가 없다. {@link
 * SliceUserRepository} 가 그 자리를 채운다 — 그래서 이 어노테이션을 쓰는 다섯 테스트가 아무것도 안 고쳐도 된다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({
  SecurityConfig.class,
  RestAuthenticationEntryPoint.class,
  RestAccessDeniedHandler.class,
  JwtProvider.class,
  AuthenticationService.class,
  SliceUserRepository.class
})
public @interface ImportSecurity {}
