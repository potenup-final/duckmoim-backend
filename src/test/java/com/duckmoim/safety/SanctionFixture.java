package com.duckmoim.safety;

import com.duckmoim.safety.domain.SanctionKind;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 제재 행을 직접 넣는 테스트 전용 빌더.
 *
 * <p><b>서비스로 만들지 않고 SQL 로 넣는 이유가 둘이다.</b> 첫째로 발효 시각을 손으로 고정해야 만료를 볼 수 있는데, 실행 경로는 시계에서 시각을 받으므로 「1년
 * 전에 걸린 경고」를 만들 수 없다. 둘째로 이미 풀린 제재({@code releasedAt} 이 찍힌 행)를 만들려면 실행과 해제를 둘 다 지나야 하고, 그러면 조회 검사가
 * 쓰기 경로의 규칙에 묶인다.
 *
 * <p>여기서 넣은 행은 JPA 의 영속성 컨텍스트를 지나지 않아 조회가 DB 에서 새로 읽는다.
 */
public final class SanctionFixture {

  private static final AtomicLong SEQUENCE = new AtomicLong();

  private static final String INSERT =
      """
      INSERT INTO sanction (user_id, kind, reason, issued_at, until, released_at,
                            created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
      """;

  private final String reason = "픽스처 제재 " + SEQUENCE.incrementAndGet();
  private long userId = 2L;
  private SanctionKind kind = SanctionKind.SUSPENDED;
  private LocalDateTime issuedAt = LocalDateTime.of(2026, 9, 1, 0, 0);
  private LocalDateTime until = LocalDateTime.of(2026, 9, 11, 0, 0);
  private LocalDateTime releasedAt;

  private SanctionFixture() {}

  public static SanctionFixture aSanction() {
    return new SanctionFixture();
  }

  public SanctionFixture userId(long userId) {
    this.userId = userId;
    return this;
  }

  /** {@code until} 도 함께 정한다 — 종류와 어긋나면 도메인이 막는 조합이라 픽스처가 알아서 맞춘다. */
  public SanctionFixture kind(SanctionKind kind) {
    this.kind = kind;
    this.until = kind.hasUntil() ? issuedAt.plusDays(10) : null;
    return this;
  }

  public SanctionFixture issuedAt(LocalDateTime issuedAtUtc) {
    this.issuedAt = issuedAtUtc;
    this.until = kind.hasUntil() ? issuedAtUtc.plusDays(10) : null;
    return this;
  }

  public SanctionFixture until(LocalDateTime untilUtc) {
    this.until = untilUtc;
    return this;
  }

  public SanctionFixture releasedAt(LocalDateTime releasedAtUtc) {
    this.releasedAt = releasedAtUtc;
    return this;
  }

  public long insert(JdbcTemplate jdbc) {
    jdbc.update(INSERT, userId, kind.name(), reason, issuedAt, until, releasedAt);

    return jdbc.queryForObject("SELECT id FROM sanction WHERE reason = ?", Long.class, reason);
  }
}
