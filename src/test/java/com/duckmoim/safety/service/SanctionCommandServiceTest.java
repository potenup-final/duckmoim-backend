package com.duckmoim.safety.service;

import static com.duckmoim.safety.SanctionFixture.aSanction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.admin.domain.AuditKind;
import com.duckmoim.admin.domain.AuditLog;
import com.duckmoim.admin.domain.AuditLogListQuery;
import com.duckmoim.admin.domain.AuditTargetType;
import com.duckmoim.admin.infra.ActedAuditLog;
import com.duckmoim.admin.infra.AuditLogRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.safety.domain.Sanction;
import com.duckmoim.safety.domain.SanctionKind;
import com.duckmoim.safety.exception.SanctionErrorCode;
import com.duckmoim.safety.infra.SanctionRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 제재 실행과 해제 (AD-04 · AD-05).
 *
 * <p>감사 로그를 흉내 내지 않고 실제 저장을 지난다. 완료 조건이 「{@code SANCTION} · {@code RELEASE} 감사 로그가 제재 실행과 같은 트랜잭션에
 * 남는다」라 <b>둘이 함께 일어나는지</b>가 검사 대상이다.
 *
 * <p>관리자는 V11 시드의 6 번('운영자'). 대상은 2 번('댓글덕후').
 *
 * <p>같은 트랜잭션인지는 여기서 볼 수 없다 — 이 클래스가 통째로 롤백된다. {@link SanctionAuditTransactionTest} 가 그것을 본다.
 */
@SpringBootTest
@Transactional
class SanctionCommandServiceTest {

  private static final long ADMIN_ID = 6L;
  private static final long USER_ID = 2L;

  @Autowired private SanctionCommandService sanctionCommandService;
  @Autowired private SanctionQueryService sanctionQueryService;
  @Autowired private SanctionRepository sanctionRepository;
  @Autowired private AuditLogRepository auditLogRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Clock clock;

  @DisplayName("네 종류를 모두 실행한다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(SanctionKind.class)
  void sanction(SanctionKind kind) {
    long sanctionId = sanctionCommandService.sanction(command(kind));

    Sanction saved = sanctionRepository.findById(sanctionId).orElseThrow();
    assertThat(saved.getKind()).isEqualTo(kind);
    assertThat(saved.getUserId()).isEqualTo(USER_ID);
    assertThat(saved.getReason()).isEqualTo("약속 불이행 신고가 세 건 접수되었습니다");
    assertThat(saved.getReleasedAt()).isNull();
  }

  @DisplayName("제재하면 SANCTION 감사 로그가 남는다.")
  @Test
  void sanctionRecords() {
    sanctionCommandService.sanction(command(SanctionKind.SUSPENDED));

    AuditLog saved = onlyOne().auditLog();
    assertThat(saved.getActorUserId()).isEqualTo(ADMIN_ID);
    assertThat(saved.getKind()).isEqualTo(AuditKind.SANCTION);
    assertThat(saved.getTargetType()).isEqualTo(AuditTargetType.USER);
    assertThat(saved.getTargetId()).isEqualTo(USER_ID);
  }

  /** 감사 로그는 고칠 수 없고 사유는 본인에게 보여주는 문장이라 길다. 사유 자체는 sanction 표에 있다. */
  @DisplayName("감사 로그에 제재 사유를 그대로 싣지 않는다.")
  @Test
  void sanctionDoesNotRecordReason() {
    sanctionCommandService.sanction(command(SanctionKind.SUSPENDED));

    assertThat(onlyOne().auditLog().getDetail()).doesNotContain("약속 불이행");
  }

  /** 도메인 6장의 제재 축이 NONE 에서만 출발한다. 덮어쓰면 앞 제재의 사유가 조용히 사라진다. */
  @DisplayName("이미 활성 제재가 있으면 409 다.")
  @Test
  void sanction_isAlreadyActive() {
    sanctionCommandService.sanction(command(SanctionKind.SUSPENDED));

    assertThatThrownBy(() -> sanctionCommandService.sanction(command(SanctionKind.BANNED)))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(SanctionErrorCode.SANCTION_ALREADY_ACTIVE);
  }

  @DisplayName("막힌 요청에는 기록도 남지 않는다.")
  @Test
  void sanction_isAlreadyActiveRecordsNothing() {
    sanctionCommandService.sanction(command(SanctionKind.SUSPENDED));

    assertThatThrownBy(() -> sanctionCommandService.sanction(command(SanctionKind.BANNED)))
        .isInstanceOf(BusinessException.class);

    assertThat(auditLogRepository.findSlice(new AuditLogListQuery(null, 20))).hasSize(1);
  }

  /** 만료된 제재는 활성이 아니므로 새로 걸 수 있다. */
  @DisplayName("지난 제재가 있으면 새로 걸 수 있다.")
  @Test
  void sanction_hasExpiredSanction() {
    aSanction()
        .userId(USER_ID)
        .kind(SanctionKind.SUSPENDED)
        .issuedAt(nowUtc().minusDays(10))
        .until(nowUtc().minusDays(1))
        .insert(jdbc);

    long sanctionId = sanctionCommandService.sanction(command(SanctionKind.WARNED));

    assertThat(sanctionRepository.findById(sanctionId)).isPresent();
  }

  @DisplayName("제재를 풀면 NONE 으로 돌아간다.")
  @Test
  void release() {
    long sanctionId = sanctionCommandService.sanction(command(SanctionKind.SUSPENDED));

    sanctionCommandService.release(USER_ID, sanctionId, ADMIN_ID);

    assertThat(sanctionRepository.findById(sanctionId).orElseThrow().getReleasedAt()).isNotNull();
    assertThat(sanctionQueryService.findActive(USER_ID)).isEmpty();
  }

  @DisplayName("풀면 RELEASE 감사 로그가 남는다.")
  @Test
  void releaseRecords() {
    long sanctionId = sanctionCommandService.sanction(command(SanctionKind.SUSPENDED));

    sanctionCommandService.release(USER_ID, sanctionId, ADMIN_ID);

    List<ActedAuditLog> found = auditLogRepository.findSlice(new AuditLogListQuery(null, 20));
    assertThat(found).hasSize(2);
    assertThat(found.get(0).auditLog().getKind()).isEqualTo(AuditKind.RELEASE);
    assertThat(found.get(0).auditLog().getTargetId()).isEqualTo(USER_ID);
  }

  @DisplayName("이미 푼 제재는 다시 풀 수 없다.")
  @Test
  void release_isAlreadyReleased() {
    long sanctionId = sanctionCommandService.sanction(command(SanctionKind.SUSPENDED));
    sanctionCommandService.release(USER_ID, sanctionId, ADMIN_ID);

    assertThatThrownBy(() -> sanctionCommandService.release(USER_ID, sanctionId, ADMIN_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(SanctionErrorCode.SANCTION_ALREADY_RELEASED);
  }

  @DisplayName("없는 제재는 404 다.")
  @Test
  void release_isMissing() {
    assertThatThrownBy(() -> sanctionCommandService.release(USER_ID, -1L, ADMIN_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(SanctionErrorCode.SANCTION_NOT_FOUND);
  }

  /** 경로에 userId 와 sanctionId 가 둘 다 있다. 남의 번호를 넣으면 엉뚱한 사람이 풀린다. */
  @DisplayName("남의 제재 번호로는 풀 수 없다.")
  @Test
  void release_belongsToOtherUser() {
    long sanctionId = sanctionCommandService.sanction(command(SanctionKind.SUSPENDED));

    assertThatThrownBy(() -> sanctionCommandService.release(3L, sanctionId, ADMIN_ID))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(SanctionErrorCode.SANCTION_NOT_FOUND);
  }

  private SanctionCommand command(SanctionKind kind) {
    LocalDateTime until = kind.hasUntil() ? nowUtc().plusDays(3) : null;

    return new SanctionCommand(USER_ID, kind, "약속 불이행 신고가 세 건 접수되었습니다", until, ADMIN_ID);
  }

  private ActedAuditLog onlyOne() {
    List<ActedAuditLog> found = auditLogRepository.findSlice(new AuditLogListQuery(null, 20));

    assertThat(found).hasSize(1);
    return found.get(0);
  }

  private LocalDateTime nowUtc() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
