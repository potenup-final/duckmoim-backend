package com.duckmoim.identity.service;

import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.admin.domain.AuditKind;
import com.duckmoim.admin.domain.AuditLog;
import com.duckmoim.admin.domain.AuditLogListQuery;
import com.duckmoim.admin.domain.AuditTargetType;
import com.duckmoim.admin.infra.ActedAuditLog;
import com.duckmoim.admin.infra.AuditLogRepository;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.identity.exception.UserErrorCode;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자가 계정을 파기하고 그 기록이 남는지 (AD-05).
 *
 * <p>기록을 흉내 내지 않고 실제 저장을 지난다. 완료 조건이 「{@code PURGE} 감사 로그가 남는다」라 <b>파기와 기록이 함께 일어나는지</b>가 검사 대상이고,
 * 둘 중 하나를 mock 으로 바꾸면 그 연결이 검증에서 빠진다.
 *
 * <p>행위자는 V11 시드의 6 번('운영자').
 *
 * <p>같은 트랜잭션인지는 여기서 볼 수 없다 — 이 클래스가 통째로 롤백된다. {@link AdminUserPurgeTransactionTest} 가 그것을 본다.
 */
@SpringBootTest
@Transactional
class AdminUserPurgeServiceTest {

  private static final long ADMIN_ID = 6L;
  private static final String REASON = "나이 확인 요청에 30일간 답이 없었습니다";

  @Autowired private AdminUserPurgeService adminUserPurgeService;
  @Autowired private AuditLogRepository auditLogRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;

  @DisplayName("계정을 파기하면 PURGE 감사 로그 한 줄이 남는다.")
  @Test
  void purgeRecords() {
    long userId = aUser().insert(jdbc);

    adminUserPurgeService.purge(userId, ADMIN_ID, REASON);

    AuditLog saved = onlyOne().auditLog();
    assertThat(saved.getActorUserId()).isEqualTo(ADMIN_ID);
    assertThat(saved.getKind()).isEqualTo(AuditKind.PURGE);
    assertThat(saved.getTargetType()).isEqualTo(AuditTargetType.USER);
    assertThat(saved.getTargetId()).isEqualTo(userId);
  }

  /** 사유가 그대로 장부에 들어간다. 나중에 왜 파기했는지를 읽을 수 있는 유일한 자리다. */
  @DisplayName("파기 사유가 감사 로그에 그대로 남는다.")
  @Test
  void purgeRecordsReason() {
    long userId = aUser().insert(jdbc);

    adminUserPurgeService.purge(userId, ADMIN_ID, REASON);

    assertThat(onlyOne().auditLog().getDetail()).isEqualTo(REASON);
  }

  /** 탈퇴가 「파기 범위는 처리방침이 정할 일」이라며 남겨 둔 것까지 비운다. */
  @DisplayName("파기하면 개인정보 컬럼이 비워진다.")
  @Test
  void purgeClearsPersonalData() {
    long userId =
        aUser()
            .profile("남는 소개", "/avatar/mine.webp")
            .lastSeenAt(LocalDateTime.of(2026, 9, 14, 0, 0))
            .insert(jdbc);

    adminUserPurgeService.purge(userId, ADMIN_ID, REASON);

    Map<String, Object> row = row(userId);
    assertThat(row.get("kakao_user_id")).isNull();
    assertThat(row.get("bio")).isNull();
    assertThat(row.get("birth_year")).isNull();
    assertThat(row.get("last_seen_at")).isNull();
    assertThat(row.get("nickname")).isNull();
    assertThat(row.get("profile_image_url")).isNull();
  }

  /** 작성자 블록이 이 행을 내부 조인으로 읽는다 (API 2-5). 지우면 그 사람의 댓글이 목록에서 통째로 빠진다. */
  @DisplayName("파기해도 회원 행은 남는다.")
  @Test
  void purgeKeepsRow() {
    long userId = aUser().insert(jdbc);

    adminUserPurgeService.purge(userId, ADMIN_ID, REASON);

    Map<String, Object> row = row(userId);
    assertThat(row.get("status")).isEqualTo("WITHDRAWN");
    assertThat(row.get("purged_at")).isNotNull();
  }

  /** 회원번호를 비우는 것만으로는 이미 발급된 Access 가 최대 30분 더 산다. 그 사이에 글을 쓰면 「탈퇴한 회원」이 쓴 글이 된다. */
  @DisplayName("파기하면 발급된 토큰이 전부 죽는다.")
  @Test
  void purgeInvalidatesTokens() {
    long userId = aUser().insert(jdbc);

    adminUserPurgeService.purge(userId, ADMIN_ID, REASON);

    assertThat(row(userId).get("tokens_invalidated_at")).isNotNull();
  }

  /** 인가가 카카오 회원번호로 판정하는데 (D-5) 파기가 그 값을 비운다. 부른 사람이 그 자리에서 스스로 잠긴다. */
  @DisplayName("자기 계정은 파기할 수 없다.")
  @Test
  void purge_self() {
    assertThatThrownBy(() -> adminUserPurgeService.purge(ADMIN_ID, ADMIN_ID, REASON))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(UserErrorCode.USER_CANNOT_PURGE_SELF);
  }

  @DisplayName("없는 회원은 파기할 수 없다.")
  @Test
  void purge_userNotFound() {
    assertThatThrownBy(() -> adminUserPurgeService.purge(9_999_999L, ADMIN_ID, REASON))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(UserErrorCode.USER_NOT_FOUND);
  }

  /** 감사 로그는 고칠 수 없다 (I-13). 두 번 부르면 일어난 일은 하나인데 장부에 두 줄이 남는다. */
  @DisplayName("이미 파기된 계정은 다시 파기할 수 없다.")
  @Test
  void purge_alreadyPurged() {
    long userId = aUser().purgedAt(LocalDateTime.of(2026, 9, 15, 1, 0)).insert(jdbc);

    assertThatThrownBy(() -> adminUserPurgeService.purge(userId, ADMIN_ID, REASON))
        .isInstanceOf(BusinessException.class)
        .extracting(thrown -> ((BusinessException) thrown).getErrorCode())
        .isEqualTo(UserErrorCode.USER_ALREADY_PURGED);
  }

  /** 순서가 반대이면 막힌 요청에도 기록이 남고, 감사 로그는 고칠 수 없다 (I-13). */
  @DisplayName("막힌 파기에는 기록도 남지 않는다.")
  @Test
  void purge_alreadyPurgedRecordsNothing() {
    long userId = aUser().purgedAt(LocalDateTime.of(2026, 9, 15, 1, 0)).insert(jdbc);

    assertThatThrownBy(() -> adminUserPurgeService.purge(userId, ADMIN_ID, REASON))
        .isInstanceOf(BusinessException.class);

    assertThat(auditLogRepository.findSlice(new AuditLogListQuery(null, 20))).isEmpty();
  }

  /**
   * 회원 행을 SQL 로 읽는다.
   *
   * <p><b>읽기 전에 밀어낸다.</b> 파기가 영속 컨텍스트 안에서만 일어나 있으면 JDBC 는 옛 행을 본다. 영속성 컨텍스트가 스스로 밀어내는 시점이 일정하지 않아서
   * — 토큰 무효화는 그 뒤에 오는 질의가 없어 안 밀린다 — 부르는 쪽이 직접 맞춘다.
   *
   * <p><b>저장소로 읽지 않는 이유.</b> {@code findById} 는 같은 영속성 컨텍스트에서 <b>메모리에 있는 엔티티</b>를 돌려주므로, 컬럼이 실제로
   * 비워져 DB 에 갔는지를 못 본다. 파기의 완료 조건이 「저장된 개인정보가 없다」라 저장을 봐야 한다.
   */
  private Map<String, Object> row(long userId) {
    entityManager.flush();
    return jdbc.queryForMap("SELECT * FROM user WHERE id = ?", userId);
  }

  private ActedAuditLog onlyOne() {
    List<ActedAuditLog> found = auditLogRepository.findSlice(new AuditLogListQuery(null, 20));
    assertThat(found).hasSize(1);
    return found.get(0);
  }
}
