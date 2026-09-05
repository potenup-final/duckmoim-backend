package com.duckmoim.common.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "duckmoim.jwt.secret=prod-profile-test-dummy-secret-not-a-real-key")
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@DisplayName("prod 프로파일에서는 API 문서가 닫혀 있다")
class OpenApiDisabledInProdTest {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("OpenAPI 스펙은 404 다")
  void hidesApiDocs() throws Exception {
    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Swagger UI 는 404 다")
  void hidesSwaggerUi() throws Exception {
    mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
    mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
  }
}
