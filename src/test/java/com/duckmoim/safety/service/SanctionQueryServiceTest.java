package com.duckmoim.safety.service;

import static com.duckmoim.safety.SanctionFixture.aSanction;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.safety.domain.SanctionKind;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 활성 제재 조회 (AD-04 · AU-12 · I-14).
 *
 * <p>실제 MySQL 로 돈다. <b>만료 판정이 SQL 이 아니라 도메인에 있다</b>는 것이 이 서비스의 요점이라, 저장소가 무엇을 거르고 무엇을 안 거르는지가 결과로
 * 드러나야 한다.
 *
 * <p>유저는 V11 시드를 쓴다 — 2 댓글덕후.
 *
 * <p>시계는 주입된 것(KST)을 그대로 쓰고, 픽스처의 시각을 「지금」 기준으로 만든다.
 */
@SpringBootTest
@Transactional
class SanctionQueryServiceTest {

  private static final long USER_ID = 2L;

  @Autowired private SanctionQueryService sanctionQueryService;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Clock clock;

  @DisplayName("제재가 없으면 비어 있고 쓸 수 있다.")
  @Test
  void findActive_hasNoSanction() {
    assertThat(sanctionQueryService.findActive(USER_ID)).isEmpty();
    assertThat(sanctionQueryService.canWrite(USER_ID)).isTrue();
  }

  @DisplayName("유효한 제재를 읽는다.")
  @Test
  void findActive() {
    aSanction()
        .userId(USER_ID)
        .kind(SanctionKind.SUSPENDED)
        .issuedAt(nowUtc().minusDays(1))
        .until(nowUtc().plusDays(3))
        .insert(jdbc);

    ActiveSanction found = sanctionQueryService.findActive(USER_ID).orElseThrow();

    assertThat(found.kind()).isEqualTo(SanctionKind.SUSPENDED);
    assertThat(found.reason()).isNotBlank();
    assertThat(found.until()).isNotNull();
  }

  /** 저장소는 「푼 적 없는 것」까지만 거른다. 만료는 도메인이 판정한다. */
  @DisplayName("기간이 지난 정지는 없는 것으로 읽힌다.")
  @Test
  void findActive_isExpired() {
    aSanction()
        .userId(USER_ID)
        .kind(SanctionKind.SUSPENDED)
        .issuedAt(nowUtc().minusDays(10))
        .until(nowUtc().minusDays(1))
        .insert(jdbc);

    assertThat(sanctionQueryService.findActive(USER_ID)).isEmpty();
    assertThat(sanctionQueryService.canWrite(USER_ID)).isTrue();
  }

  @DisplayName("풀린 제재는 없는 것으로 읽힌다.")
  @Test
  void findActive_isReleased() {
    aSanction()
        .userId(USER_ID)
        .kind(SanctionKind.BANNED)
        .issuedAt(nowUtc().minusDays(1))
        .releasedAt(nowUtc())
        .insert(jdbc);

    assertThat(sanctionQueryService.findActive(USER_ID)).isEmpty();
  }

  @DisplayName("1년이 지난 경고는 없는 것으로 읽힌다.")
  @Test
  void findActive_isExpiredWarning() {
    aSanction()
        .userId(USER_ID)
        .kind(SanctionKind.WARNED)
        .issuedAt(nowUtc().minusYears(2))
        .insert(jdbc);

    assertThat(sanctionQueryService.findActive(USER_ID)).isEmpty();
  }

  /** I-14 가 걸린 자리다. WARNED 만 쓰기를 막지 않는다. */
  @DisplayName("경고만 쓰기를 막지 않는다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(SanctionKind.class)
  void canWrite(SanctionKind kind) {
    aSanction().userId(USER_ID).kind(kind).issuedAt(nowUtc().minusDays(1)).insert(jdbc);

    assertThat(sanctionQueryService.canWrite(USER_ID)).isEqualTo(kind == SanctionKind.WARNED);
  }

  @DisplayName("남의 제재는 내 쓰기를 막지 않는다.")
  @Test
  void canWrite_hasOtherUserSanction() {
    aSanction().userId(3L).kind(SanctionKind.BANNED).issuedAt(nowUtc().minusDays(1)).insert(jdbc);

    assertThat(sanctionQueryService.canWrite(USER_ID)).isTrue();
  }

  private LocalDateTime nowUtc() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
