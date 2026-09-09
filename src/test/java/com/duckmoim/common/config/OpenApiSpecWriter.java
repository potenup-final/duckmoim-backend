package com.duckmoim.common.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OpenAPI 스펙을 파일로 떨어뜨린다. {@code apiSpec} 태스크가 이 클래스만 골라 돌리고, {@code api-docs.yml} 이 그 결과를 Pages 에
 * 배포한다.
 *
 * <p>요구사항 검증이 아니라 산출물 생성이라 {@code test} 태스크에서 뺐다. 테스트 컨벤션이 「요구사항 명세의 검증 기준이 곧 테스트 목록」이라고 정했으므로, 여기
 * 섞이면 그 목록이 흐려진다. 이름을 {@code *Test} 로 두지 않은 것도 같은 이유다.
 *
 * <p>그래도 JUnit 을 쓰는 이유는 스펙이 애플리케이션 컨텍스트를 요구하기 때문이다. {@code bootRun} 을 CI 에서 띄우면 DB·포트·종료를 새로 다뤄야
 * 하는데, 테스트 쪽에는 Testcontainers 가 이미 있다.
 *
 * <p>{@code docs} 프로파일로 뽑는다. 기본 프로파일이 {@code local} 이라 그냥 뽑으면 그 프로파일에만 있는 {@code
 * DevTokenController} 가 스펙에 실린다. {@code prod} 로는 뽑을 수 없다 — 거기서는 문서를 닫았고 {@link
 * OpenApiDisabledInProdTest} 가 그것을 지킨다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("docs")
@DisplayName("OpenAPI 스펙을 파일로 떨어뜨린다")
class OpenApiSpecWriter {

  private static final Path OUT = Path.of("build", "openapi", "openapi.json");

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("build/openapi/openapi.json 에 스펙을 쓴다")
  void writesSpec() throws Exception {
    String spec =
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Files.createDirectories(OUT.getParent());
    Files.writeString(OUT, spec, StandardCharsets.UTF_8);
  }
}
