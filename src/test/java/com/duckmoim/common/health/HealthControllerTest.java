package com.duckmoim.common.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.presentation.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code @WebMvcTest} 는 {@code @Controller} 계열만 스캔하고 {@code @Configuration} 은 집지 않는다. 그래서 {@link
 * SecurityConfig} 를 명시적으로 가져오지 않으면 Spring Boot 기본 보안이 걸려 모든 요청이 401 이 된다.
 *
 * <p>필터를 끄는 방법({@code addFilters = false})도 있지만 쓰지 않는다 — 컨트롤러 슬라이스에서 봐야 하는 것에 인증·인가가 들어 있어서, 끄면 그
 * 검증이 사라진다.
 */
@WebMvcTest(HealthController.class)
@Import(SecurityConfig.class)
class HealthControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("헬스체크는 200 과 status=UP 을 반환한다")
  void returnsUp() throws Exception {
    mockMvc
        .perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }
}
