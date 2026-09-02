package com.duckmoim.common.config;

import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

@Configuration
@OpenAPIDefinition(
	info = @Info(
		title = "DuckMoim API",
		version = "v1",
		description = "덕모임 백엔드 API 문서"
	)
)
public class OpenApiConfig {
}
