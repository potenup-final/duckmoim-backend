package com.duckmoim.safety;

import com.duckmoim.safety.domain.ReportReason;
import com.duckmoim.safety.domain.ReportStatus;
import com.duckmoim.safety.domain.ReportTargetType;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 신고 행을 직접 넣는 테스트 전용 빌더.
 *
 * <p><b>서비스로 만들지 않고 SQL 로 넣는 이유가 둘이다.</b> 첫째로 접수 시각을 손으로 고정해야 커서 경계를 볼 수 있는데, 접수 경로는 {@code
 * UTC_TIMESTAMP(6)} 에 맡겨 같은 시각을 여러 건 만들 수 없다. 둘째로 접수 경로에는 유니크 제약(신고자·대상)이 걸려 있어 한 신고자가 같은 대상에 여러 건을
 * 만들 수 없는데, 목록 검사에는 건수가 필요하다.
 *
 * <p>여기서 넣은 행은 JPA 의 영속성 컨텍스트를 지나지 않아 조회가 DB 에서 새로 읽는다.
 */
public final class ReportFixture {

  private static final AtomicLong SEQUENCE = new AtomicLong();

  private static final String INSERT =
      """
      INSERT INTO report (reporter_id, target_type, target_id, reason, detail, status,
                          created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, COALESCE(?, UTC_TIMESTAMP(6)), UTC_TIMESTAMP(6))
      """;

  private final String detail = "픽스처 신고 " + SEQUENCE.incrementAndGet();
  private long reporterId = 4L;
  private ReportTargetType targetType = ReportTargetType.USER;
  private long targetId = 2L;
  private ReportReason reason = ReportReason.ABUSE;
  private ReportStatus status = ReportStatus.PENDING;
  private LocalDateTime createdAt;

  private ReportFixture() {}

  public static ReportFixture aReport() {
    return new ReportFixture();
  }

  public ReportFixture reporterId(long reporterId) {
    this.reporterId = reporterId;
    return this;
  }

  public ReportFixture target(ReportTargetType targetType, long targetId) {
    this.targetType = targetType;
    this.targetId = targetId;
    return this;
  }

  public ReportFixture reason(ReportReason reason) {
    this.reason = reason;
    return this;
  }

  public ReportFixture status(ReportStatus status) {
    this.status = status;
    return this;
  }

  /**
   * 접수 시각을 고정한다. <b>커서 경계 검증에 필요하다</b> — 같은 시각에 접수된 신고가 페이지 경계에 걸리는 경우를 만들어야 하는데, {@code
   * UTC_TIMESTAMP(6)} 에 맡기면 마이크로초가 갈려 같은 시각이 안 만들어진다.
   *
   * <p>주지 않으면 현재 시각이다. 저장은 UTC 다.
   */
  public ReportFixture createdAt(LocalDateTime createdAtUtc) {
    this.createdAt = createdAtUtc;
    return this;
  }

  public long insert(JdbcTemplate jdbc) {
    jdbc.update(
        INSERT,
        reporterId,
        targetType.name(),
        targetId,
        reason.name(),
        detail,
        status.name(),
        createdAt);

    return jdbc.queryForObject("SELECT id FROM report WHERE detail = ?", Long.class, detail);
  }
}
