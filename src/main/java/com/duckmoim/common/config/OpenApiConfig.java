package com.duckmoim.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@OpenAPIDefinition(
    info = @Info(title = "DuckMoim API", version = "v1", description = "덕모임 백엔드 API 문서"),
    security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH))
@SecurityScheme(
    name = OpenApiConfig.BEARER_AUTH,
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT")
public class OpenApiConfig {

  public static final String BEARER_AUTH = "bearerAuth";

  /** 공개되는 문서가 가리키는 주소. ALB 가 받는 그 주소다 (배포-파이프라인.md 「HTTPS (2026-09-08 완료)」). */
  private static final String PUBLIC_API_URL = "https://api.duckmoim.com";

  /**
   * 공개되는 스펙의 서버 주소를 박는다.
   *
   * <p>springdoc 은 {@code servers} 가 없으면 들어온 요청에서 유도한다. 스펙을 MockMvc 로 뽑기 때문에({@code
   * OpenApiSpecWriter}) 그 값이 {@code http://localhost} 가 되고, 그대로 Pages 에 올라가면 문서를 읽는 사람과 코드 생성기가 부를
   * 수 없는 주소를 받는다.
   *
   * <p><b>{@code docs} 프로파일에만 건다.</b> {@link OpenAPIDefinition} 에 직접 적으면 로컬에서 띄운 Swagger 도 이 주소를
   * 잡아, 개발자가 「Try it out」 을 누르는 순간 운영을 부른다. 스펙을 뽑는 쪽이 이미 그 프로파일이라 조건이 한 곳에서 맞는다.
   */
  @Bean
  @Profile("docs")
  OpenApiCustomizer publishedServerUrl() {
    return openApi -> openApi.setServers(List.of(new Server().url(PUBLIC_API_URL)));
  }
}
