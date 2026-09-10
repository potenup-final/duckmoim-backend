package com.duckmoim.common.health;

import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.duckmoim.auth.domain.AuthUser;
import com.duckmoim.auth.domain.TokenProvider;
import com.duckmoim.identity.domain.SignupStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Actuator 로 <b>무엇을 열었는지</b>를 못박는다.
 *
 * <p>노출 목록은 {@code application.yml} 한 줄이고, 거기에 이름 하나를 더하는 것으로 {@code /actuator/env} 가 열린다. 그 응답에는
 * {@code DB_PASSWORD} · {@code JWT_SECRET} · {@code INGEST_KEY} 가 평문으로 들어 있고, 8080 이 {@code
 * 0.0.0.0/0} 이라 여는 즉시 인터넷에서 읽힌다. 되돌릴 수 없는 사고라 한 줄의 실수를 테스트가 잡게 한다.
 *
 * <p>닫힘을 <b>토큰을 들고</b> 확인하는 이유 — 토큰 없이 찌르면 {@code anyRequest().authenticated()} 가 먼저 401 을 낸다. 그러면
 * 「인가로 막혔다」와 「애초에 없다」가 구분되지 않아서, 나중에 누가 그 경로를 {@code INFRA} 에 올리는 순간 초록불인 채로 열린다.
 *
 * <p><b>지표 수출을 되살려서 연다.</b> {@code @SpringBootTest} 는 {@code
 * management.defaults.metrics.export.enabled=false} 를 기본으로 넣는다 — 테스트가 지표를 밖으로 밀지 않게 하는 의도된 동작이다.
 * 그대로 두면 {@code /actuator/prometheus} 가 이 컨텍스트에 아예 없어서, 노출을 확인하려던 검사가 404 를 보고 <b>노출을 지워도 초록불</b>이
 * 된다. 여기서만 그 값을 덮어 운영과 같은 구성으로 본다.
 *
 * <p>인가 등급 자체는 {@code EndpointGradeTest} 의 표가 본다. 여기는 노출 범위만 본다.
 */
@SpringBootTest(properties = "management.prometheus.metrics.export.enabled=true")
@AutoConfigureMockMvc
@DisplayName("Actuator 노출 범위")
class ActuatorExposureTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private TokenProvider tokenProvider;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private HealthContributorRegistry healthContributorRegistry;

  @Test
  @DisplayName("헬스체크는 토큰 없이 200 과 status=UP 을 반환한다.")
  void healthIsOpenWithoutToken() throws Exception {
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  /**
   * 이 티켓의 본론이다. {@code /api/health} 는 상수를 돌려주므로 DB 가 죽어도 UP 이고, 그 값을 믿는 배포 전환 게이트가 <b>DB 에 못 붙는
   * 버전으로 전환</b>한다. {@code db} 기여자가 등록돼 있어야 {@code /actuator/health} 가 커넥션을 실제로 얻어 본다.
   */
  @Test
  @DisplayName("헬스체크는 DB 연결을 검사 항목으로 갖는다.")
  void healthChecksDatabaseConnection() {
    assertThat(healthContributorRegistry.stream().map(contributor -> contributor.getName()))
        .contains("db");
  }

  /**
   * 디스크는 검사 항목이 아니다.
   *
   * <p>기본 임계가 여유 10MB 이고 루트 볼륨이 8GB 라, 걸리면 두 인스턴스가 같은 속도로 차서 <b>동시에</b> DOWN 이 된다. ALB 는 healthy
   * 대상이 0 이 되어 전부 503 을 내는데, 정작 DB 는 멀쩡해서 요청은 처리할 수 있는 상태다. 디스크 경보는 대시보드 몫이다.
   */
  @Test
  @DisplayName("헬스체크는 디스크 여유를 검사 항목으로 갖지 않는다.")
  void healthDoesNotCheckDiskSpace() {
    assertThat(healthContributorRegistry.stream().map(contributor -> contributor.getName()))
        .doesNotContain("diskSpace");
  }

  /** 지표 이름과 실패 사유가 그대로 나가면 내부 구성이 드러난다. 값이 null 인지가 아니라 <b>키가 없는지</b>를 본다. */
  @Test
  @DisplayName("헬스체크 응답은 검사 항목별 상세를 담지 않는다.")
  void healthHidesComponentDetails() throws Exception {
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components").doesNotExist());
  }

  @Test
  @DisplayName("지표 수집 경로는 토큰 없이 열려 있다.")
  void prometheusIsOpenWithoutToken() throws Exception {
    mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
  }

  /**
   * 목록 엔드포인트가 스스로 신고하는 노출 범위를 본다.
   *
   * <p>아래 {@link #unexposedEndpointsAreAbsent} 는 <b>내가 적은 이름</b>만 검사하므로, 앞으로 생길 엔드포인트를 누가 열어도
   * 초록불이다. 이 검사는 반대로 앱이 가진 목록을 읽어서, 열려 있는 것이 둘뿐인지를 확인한다.
   *
   * <p>{@code /actuator} 자체는 인가를 통과한 요청에 200 을 낸다. 여는 것을 막지 않는 이유는 <b>이미 열린 것만 나열</b>하기 때문이고, 익명에게는
   * {@code INFRA} 에 없어서 401 이다.
   */
  @Test
  @DisplayName("관리 경로 목록에는 헬스체크와 지표만 있다.")
  void linksListHealthAndMetricsOnly() throws Exception {
    mockMvc
        .perform(get("/actuator").headers(signedInHeaders()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$._links.health").exists())
        .andExpect(jsonPath("$._links.prometheus").exists())
        .andExpect(jsonPath("$._links.env").doesNotExist())
        .andExpect(jsonPath("$._links.configprops").doesNotExist())
        .andExpect(jsonPath("$._links.beans").doesNotExist());
  }

  /**
   * 열지 않은 엔드포인트는 인가를 통과한 요청에도 없다.
   *
   * <p>{@code env} 와 {@code configprops} 가 비밀을 흘리는 자리이고, {@code heapdump} 는 힙 전체를 파일로 내려주므로 그 안에
   * 토큰과 커넥션 문자열이 함께 들어 있다. {@code beans} · {@code loggers} · {@code threaddump} 는 비밀은 아니지만 내부 구조를
   * 그대로 보여준다.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/actuator/env",
        "/actuator/configprops",
        "/actuator/beans",
        "/actuator/heapdump",
        "/actuator/loggers",
        "/actuator/threaddump",
        "/actuator/mappings"
      })
  @DisplayName("열지 않은 관리 경로는 토큰이 있어도 404 다.")
  void unexposedEndpointsAreAbsent(String path) throws Exception {
    mockMvc.perform(get(path).headers(signedInHeaders())).andExpect(status().isNotFound());
  }

  private HttpHeaders signedInHeaders() {
    long userId = aUser().status(SignupStatus.ACTIVE).insert(jdbcTemplate);
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenProvider.createAccessToken(new AuthUser(userId, true, false)));
    return headers;
  }
}
