package com.duckmoim.safety.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.admin.domain.AuditLogListQuery;
import com.duckmoim.admin.infra.AuditLogRepository;
import com.duckmoim.safety.domain.SanctionKind;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 감사 로그가 제재 실행과 <b>같은 트랜잭션</b>에 남는지 (AD-04 · AD-05 · I-13).
 *
 * <p>이 티켓의 완료 조건 한 줄이 그것이다 — <i>"{@code SANCTION} · {@code RELEASE} 감사 로그가 제재 실행과 같은 트랜잭션에 남는다
 * (AD-05 · 도메인 3.3 예외)"</i>.
 *
 * <p><b>클래스에 {@code @Transactional} 을 붙이지 않는다.</b> 붙이면 테스트가 통째로 롤백되어 「부르는 쪽이 롤백하면 기록도 사라진다」를 볼 수 없다
 * — 무엇이 롤백을 일으켰는지 구분되지 않는다. 여기서는 트랜잭션을 직접 열고 직접 되돌린다.
 *
 * <p><b>이 검사가 지키는 것.</b> 기록기가 {@code REQUIRES_NEW} 로 떨어지면 제재가 실패해도 기록만 남아 <b>일어나지 않은 제재</b>가 장부에
 * 오르고, 감사 로그는 고칠 수 없어 그 줄이 영영 남는다.
 *
 * <p>롤백되므로 남기는 행이 없다. 실패할 때만 흔적이 남고, 그때는 이 검사가 빨간불이다.
 */
@SpringBootTest
class SanctionAuditTransactionTest {

  private static final long ADMIN_ID = 6L;
  private static final long USER_ID = 2L;

  @Autowired private SanctionCommandService sanctionCommandService;
  @Autowired private AuditLogRepository auditLogRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private Clock clock;

  @DisplayName("부르는 쪽이 롤백하면 감사 로그도 남지 않는다.")
  @Test
  void recordJoinsCallerTransaction() {
    long before = countAuditLogs();

    transactionTemplate.executeWithoutResult(
        status -> {
          sanctionCommandService.sanction(
              new SanctionCommand(
                  USER_ID,
                  SanctionKind.SUSPENDED,
                  "약속 불이행 신고가 세 건 접수되었습니다",
                  nowUtc().plusDays(3),
                  ADMIN_ID));

          status.setRollbackOnly();
        });

    assertThat(countAuditLogs()).isEqualTo(before);
  }

  private long countAuditLogs() {
    return auditLogRepository.findSlice(new AuditLogListQuery(null, 1000)).size();
  }

  private LocalDateTime nowUtc() {
    return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  }
}
