package com.duckmoim.admin;

import com.duckmoim.admin.domain.AuditKind;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 감사 로그 행을 직접 넣는 테스트 전용 빌더.
 *
 * <p><b>기록기로 만들지 않고 SQL 로 넣는 이유가 둘이다.</b> 첫째로 {@code at} 을 손으로 고정해야 커서 경계를 볼 수 있는데, 기록기는 시계에서 시각을
 * 받으므로 같은 시각을 여러 건 만들 수 없다. 둘째로 저장소 검사가 기록기를 지나면 둘 중 어느 쪽이 틀렸는지 좁혀지지 않는다.
 *
 * <p>여기서 넣은 행은 JPA 의 영속성 컨텍스트를 지나지 않아 조회가 DB 에서 새로 읽는다.
 */
public final class AuditLogFixture {

  private static final AtomicLong SEQUENCE = new AtomicLong();

  private static final String INSERT =
      """
      INSERT INTO audit_log (actor_user_id, kind, target_type, target_id, detail, at)
      VALUES (?, ?, ?, ?, ?, COALESCE(?, UTC_TIMESTAMP(6)))
      """;

  private final String detail = "픽스처 기록 " + SEQUENCE.incrementAndGet();
  private long actorUserId = 6L;
  private AuditKind kind = AuditKind.SECRET_READ;
  private long targetId = 1L;
  private LocalDateTime at;

  private AuditLogFixture() {}

  public static AuditLogFixture anAuditLog() {
    return new AuditLogFixture();
  }

  public AuditLogFixture actorUserId(long actorUserId) {
    this.actorUserId = actorUserId;
    return this;
  }

  public AuditLogFixture kind(AuditKind kind) {
    this.kind = kind;
    return this;
  }

  public AuditLogFixture targetId(long targetId) {
    this.targetId = targetId;
    return this;
  }

  /**
   * 행위 시각을 고정한다. <b>커서 경계 검증에 필요하다</b> — 정렬 키가 같은 데이터를 만들어야 하는데 {@code UTC_TIMESTAMP(6)} 에 맡기면
   * 마이크로초가 갈려 같은 시각이 만들어지지 않는다.
   *
   * <p>주지 않으면 현재 시각이다. 저장은 UTC 다.
   */
  public AuditLogFixture at(LocalDateTime atUtc) {
    this.at = atUtc;
    return this;
  }

  public long insert(JdbcTemplate jdbc) {
    jdbc.update(INSERT, actorUserId, kind.name(), kind.targetType().name(), targetId, detail, at);

    return jdbc.queryForObject("SELECT id FROM audit_log WHERE detail = ?", Long.class, detail);
  }
}
