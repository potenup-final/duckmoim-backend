package com.duckmoim.common.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("기본 프로파일에서는 API 문서가 열려 있다")
class OpenApiExposureTest {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("OpenAPI 스펙을 200 으로 내려준다")
  void servesApiDocs() throws Exception {
    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("문서에 Bearer 토큰 입력란이 노출된다")
  void exposesBearerScheme() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"));
  }

  @Test
  @DisplayName("Swagger UI 로 이동시킨다")
  void servesSwaggerUi() throws Exception {
    mockMvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
  }
}
