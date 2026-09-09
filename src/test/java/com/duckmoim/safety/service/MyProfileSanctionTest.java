package com.duckmoim.safety.service;

import static com.duckmoim.safety.SanctionFixture.aSanction;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.safety.domain.SanctionKind;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 제재 안내가 실제 응답까지 닿는지 (AU-12).
 *
 * <p>AD-04 의 검증 기준이 「<b>정지 유저 로그인 시</b> 안내 + 사유 노출」이라, 포트 구현이 맞는 값을 돌려주는 것만으로는 부족하다 — {@code
 * identity} 가 그 포트를 실제로 부르고 응답 필드까지 옮기는지가 이 요구사항이다. {@link SanctionViewReaderTest} 가 포트를 보고, 여기가 그
 * 이음매를 본다.
 *
 * <p>유저는 V11 시드의 2 번('댓글덕후').
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MyProfileSanctionTest {

  private static final long USER_ID = 2L;

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Clock clock;

  @DisplayName("정지 유저의 /users/me 에 안내와 사유가 실린다.")
  @Test
  void carriesSanction() throws Exception {
    LocalDateTime now = nowUtc();
    aSanction()
        .userId(USER_ID)
        .kind(SanctionKind.SUSPENDED)
        .issuedAt(now.minusDays(1))
        .until(now.plusDays(3))
        .insert(jdbc);

    mockMvc
        .perform(get("/api/v1/users/me").headers(bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sanction.kind").value("SUSPENDED"))
        .andExpect(jsonPath("$.sanction.reason").isNotEmpty())
        .andExpect(jsonPath("$.sanction.until").isNotEmpty());
  }

  /** 키를 빼지 않는다 — 제재 상태에 따라 키가 생겼다 없어졌다 하면 클라이언트가 그것을 다뤄야 한다. */
  @DisplayName("제재가 없어도 sanction 키가 있고 kind 가 NONE 이다.")
  @Test
  void carriesNoneWithoutSanction() throws Exception {
    mockMvc
        .perform(get("/api/v1/users/me").headers(bearer()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sanction.kind").value("NONE"))
        .andExpect(jsonPath("$.sanction.until").doesNotExist());
  }

  /** 정지당한 사람이 자기가 왜 정지됐는지 볼 수 있어야 한다. 관문이 이 경로를 막으면 안 된다. */
  @DisplayName("정지 중에도 내 정보는 볼 수 있다.")
  @Test
  void isReadableWhileSanctioned() throws Exception {
    aSanction()
        .userId(USER_ID)
        .kind(SanctionKind.BANNED)
        .issuedAt(nowUtc().minusDays(1))
        .insert(jdbc);

    mockMvc.perform(get("/api/v1/users/me").headers(bearer())).andExpect(status().isOk());
  }

  private HttpHeaders bearer() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(new AuthUser(USER_ID, true, false)));
    return headers;
  }

  private LocalDateTime nowUtc() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
