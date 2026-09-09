package com.duckmoim.catalog.service;

import static com.duckmoim.catalog.EventFixture.anEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.catalog.domain.EventCrawl;
import com.duckmoim.catalog.domain.EventKind;
import com.duckmoim.catalog.domain.EventSource;
import com.duckmoim.catalog.domain.PlaceKind;
import com.duckmoim.catalog.domain.SubjectType;
import com.duckmoim.catalog.domain.Trust;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 같은 행사를 동시에 적재해도 중복이 생기지 않는다 (EV-03 · I-12).
 *
 * <p>검증 기준이 「재실행해도 중복 생성 없음」인데, {@link EventIngestServiceTest} 가 보는 <b>순차 재실행</b>과 여기서 보는 <b>동시
 * 실행</b>은 다른 것을 증명한다. 순차는 「조회 후 없으면 생성」 흐름이 맞다는 것이고, 동시는 그 흐름이 <b>겹쳤을 때 최후의 방어선이 있다</b>는 것이다. 그
 * 방어선이 {@code uk_event_external_id} 다 — 도메인-모델링.md 「5. 불변식」이 I-12 의 이중 방어를 유니크 제약으로 정했고, 이중 방어가 DB
 * 제약이면 검증은 통합 테스트라고 같은 문서가 적었다.
 *
 * <p><b>{@code @Transactional} 이 없다.</b> 테스트 컨벤션이 <i>"별도 스레드는 테스트의 트랜잭션에 참여하지 않아서, 롤백을 걸면 테스트가 항상
 * 통과하는 상태가 된다"</i> 고 못박았다. 그래서 넣은 행을 손으로 지운다.
 *
 * <p><b>둘 다 성공하기를 기대하지 않는다.</b> 뒤에 온 트랜잭션은 유니크 제약에 걸려 통째로 롤백된다 — 벌크 적재가 「전부 성공 아니면 전부 롤백」이라 그것이 설계된
 * 동작이다. 크롤러 쪽에서는 그 실행이 실패로 요약에 남고 다음 잡이 다시 전량을 보낸다. 실제로 두 잡이 겹치는 것은 워크플로 동시성 제어가 막고 있어서, 이 경로는 그
 * 제어가 풀렸을 때를 위한 바닥이다.
 *
 * <p>그래서 <b>여기서 보는 것은 성공 횟수가 아니라 남은 행 수</b>다. 둘 중 몇이 살아남았든 {@code external_id} 하나에 행은 하나여야 한다.
 */
@SpringBootTest
@DisplayName("행사 적재의 동시성")
class EventIngestConcurrencyTest {

  private static final String EXTERNAL_ID = "kopis_concurrent";
  private static final int THREADS = 2;

  @Autowired private EventIngestService eventIngestService;
  @Autowired private JdbcTemplate jdbc;

  private String districtCode;
  private long regionId;

  @BeforeEach
  void setUp() {
    districtCode = jdbc.queryForObject("SELECT code FROM region LIMIT 1", String.class);
    regionId = jdbc.queryForObject("SELECT id FROM region LIMIT 1", Long.class);
    jdbc.update("DELETE FROM event WHERE external_id = ?", EXTERNAL_ID);
  }

  /** 롤백이 없으니 손으로 지운다. 남기면 다음 테스트의 건수에 섞인다. */
  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM event WHERE external_id = ?", EXTERNAL_ID);
  }

  @DisplayName("같은 행사를 동시에 적재해도 행이 하나만 남는다.")
  @Test
  void ingest_isConcurrent() throws Exception {
    // given — 제출을 마친 뒤 한꺼번에 풀어야 경합이 실제로 겹친다
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger succeeded = new AtomicInteger();

    // when
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    try {
      for (int i = 0; i < THREADS; i++) {
        pool.submit(
            () -> {
              start.await();
              try {
                eventIngestService.ingest(command());
                succeeded.incrementAndGet();
              } catch (RuntimeException ignored) {
                // 뒤에 온 쪽은 유니크 제약에 걸려 롤백된다. 그것이 설계된 동작이다.
              }
              return null;
            });
      }
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    // then — 적어도 하나는 들어가야 하고, 두 개가 되면 안 된다
    Long rows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM event WHERE external_id = ?", Long.class, EXTERNAL_ID);

    assertThat(rows).isEqualTo(1);
    assertThat(succeeded.get()).isPositive();
  }

  /**
   * 이중 방어가 실제로 있는지 본다 (I-12).
   *
   * <p>위 동시성 테스트만으로는 부족하다. 두 스레드가 우연히 순서대로 돌면 뒤에 온 쪽이 <b>갱신</b>이 되어 둘 다 성공하고, 그래도 행은 하나라 초록불이 난다 —
   * 즉 제약이 없어도 통과할 수 있다. 그래서 제약 자체를 직접 찌른다.
   *
   * <p>service 를 지나지 않고 SQL 로 넣는다. 여기서 보는 것은 애플리케이션의 판정이 아니라 <b>DB 가 마지막에 거부하는가</b>다.
   */
  @DisplayName("같은 외부 식별자를 두 번 넣으면 DB 가 거부한다.")
  @Test
  void uniqueConstraintRejectsDuplicate() {
    anEvent().externalId(EXTERNAL_ID).regionId(regionId).insert(jdbc);

    assertThatThrownBy(() -> anEvent().externalId(EXTERNAL_ID).regionId(regionId).insert(jdbc))
        .isInstanceOf(DuplicateKeyException.class);
  }

  private EventIngestCommand command() {
    return new EventIngestCommand(List.of(new EventIngestCommand.Item(crawl(), districtCode)));
  }

  private EventCrawl crawl() {
    return new EventCrawl(
        EXTERNAL_ID,
        EventSource.KOPIS,
        EventKind.CONCERT,
        SubjectType.IDOL,
        Trust.PARSED,
        "동시성 대상",
        null,
        LocalDate.now(),
        LocalDate.now().plusMonths(1),
        null,
        null,
        null,
        null,
        "https://kopis.or.kr/1",
        null,
        null,
        null,
        "KSPO DOME",
        "서울 송파구 올림픽로 424",
        new BigDecimal("37.5209000"),
        new BigDecimal("127.0736000"),
        PlaceKind.CONCERT_HALL);
  }
}
