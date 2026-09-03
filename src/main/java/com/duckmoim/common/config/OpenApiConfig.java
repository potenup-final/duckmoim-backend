package com.duckmoim.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
    info = @Info(title = "DuckMoim API", version = "v1", description = "덕모임 백엔드 API 문서"))
public class OpenApiConfig {}
