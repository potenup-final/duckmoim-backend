package com.duckmoim.safety.service;

import static com.duckmoim.safety.SanctionFixture.aSanction;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.identity.service.SanctionReader;
import com.duckmoim.identity.service.SanctionView;
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
 * {@code /users/me} 에 실리는 제재 상태 (AU-12).
 *
 * <p><b>포트로 주입받는다.</b> {@code SanctionReader} 타입으로 받아, {@code identity} 가 실제로 이 구현을 쓰게 됐는지까지 확인한다 —
 * 구현 클래스로 받으면 「빈은 있는데 포트에 안 꽂힌」 상태를 못 본다.
 *
 * <p>유저는 V11 시드의 2 번('댓글덕후').
 */
@SpringBootTest
@Transactional
class SanctionViewReaderTest {

  private static final long USER_ID = 2L;

  @Autowired private SanctionReader sanctionReader;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Clock clock;

  /** 응답에서 키를 빼지 않는다 — 클라이언트가 키가 생기는 날 코드를 고쳐야 한다. */
  @DisplayName("제재가 없으면 NONE 이다.")
  @Test
  void read_hasNoSanction() {
    SanctionView view = sanctionReader.read(USER_ID);

    assertThat(view.kind()).isEqualTo("NONE");
    assertThat(view.reason()).isNull();
    assertThat(view.until()).isNull();
  }

  @DisplayName("제재 종류가 그대로 실린다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(SanctionKind.class)
  void read_carriesKind(SanctionKind kind) {
    sanction(kind);

    assertThat(sanctionReader.read(USER_ID).kind()).isEqualTo(kind.name());
  }

  /** 「사유는 본인에게 그대로 보여준다」 (화면 계약 「제재 상태」 · AD-04 · AU-12). */
  @DisplayName("정지 유저에게 안내와 사유가 실린다.")
  @Test
  void read_carriesReason() {
    sanction(SanctionKind.SUSPENDED);

    SanctionView view = sanctionReader.read(USER_ID);

    assertThat(view.kind()).isEqualTo("SUSPENDED");
    assertThat(view.reason()).isNotBlank();
    assertThat(view.issuedAt()).isNotNull();
  }

  /** 화면 계약이 「until 은 SUSPENDED 일 때만 값이 있다」고 정했다. */
  @DisplayName("until 은 기간 정지에만 있다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(SanctionKind.class)
  void read_carriesUntilOnlyForSuspension(SanctionKind kind) {
    sanction(kind);

    assertThat(sanctionReader.read(USER_ID).until() != null).isEqualTo(kind.hasUntil());
  }

  @DisplayName("만료된 제재는 NONE 으로 읽힌다.")
  @Test
  void read_isExpired() {
    aSanction()
        .userId(USER_ID)
        .kind(SanctionKind.SUSPENDED)
        .issuedAt(nowUtc().minusDays(10))
        .until(nowUtc().minusDays(1))
        .insert(jdbc);

    assertThat(sanctionReader.read(USER_ID).kind()).isEqualTo("NONE");
  }

  /** 저장은 UTC 이고 응답은 KST 오프셋을 포함한다 (API-컨벤션.md 「필드 표기 규칙」). */
  @DisplayName("시각은 KST 오프셋을 달고 나간다.")
  @Test
  void read_isKst() {
    sanction(SanctionKind.SUSPENDED);

    assertThat(sanctionReader.read(USER_ID).issuedAt().getOffset())
        .isEqualTo(java.time.ZoneOffset.ofHours(9));
  }

  private void sanction(SanctionKind kind) {
    aSanction()
        .userId(USER_ID)
        .kind(kind)
        .issuedAt(nowUtc().minusDays(1))
        .until(kind.hasUntil() ? nowUtc().plusDays(3) : null)
        .insert(jdbc);
  }

  private LocalDateTime nowUtc() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
