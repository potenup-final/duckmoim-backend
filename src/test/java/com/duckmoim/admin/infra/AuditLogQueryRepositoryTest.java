package com.duckmoim.admin.infra;

import static com.duckmoim.admin.AuditLogFixture.anAuditLog;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.admin.domain.AuditKind;
import com.duckmoim.admin.domain.AuditLog;
import com.duckmoim.admin.domain.AuditLogCursor;
import com.duckmoim.admin.domain.AuditLogListQuery;
import com.duckmoim.admin.domain.AuditTargetType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감사 로그 목록의 정렬 · 커서 경계 · 행위자 조인 (AD-05).
 *
 * <p>실제 MySQL 로 돈다. 커서 경계는 정렬과 튜플 비교가 개입해서 mock 으로는 검증되지 않고, 테스트 컨벤션이 H2 도 금지했다.
 *
 * <p><b>행위 시각을 손으로 고정한다.</b> 정렬 키가 같은 데이터를 만들어 경계를 봐야 하는데 {@code UTC_TIMESTAMP(6)} 에 맡기면 마이크로초가 갈려
 * 같은 시각이 만들어지지 않는다.
 *
 * <p>행위자는 V11 시드를 쓴다 — 6 이 관리자('운영자')이고 V31 이 그를 화이트리스트에 넣는다.
 */
@SpringBootTest
@Transactional
class AuditLogQueryRepositoryTest {

  private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 1, 0, 0);

  private static final long ADMIN_ID = 6L;
  private static final String ADMIN_NICKNAME = "운영자";

  @Autowired private AuditLogRepository auditLogRepository;
  @Autowired private JdbcTemplate jdbc;

  @DisplayName("감사 로그는 최신순으로 온다.")
  @Test
  void findSlice() {
    long oldest = logged(BASE.plusMinutes(1));
    long newest = logged(BASE.plusMinutes(3));
    long middle = logged(BASE.plusMinutes(2));

    List<ActedAuditLog> found = auditLogRepository.findSlice(query(null, 20));

    assertThat(idsOf(found)).containsExactly(newest, middle, oldest);
  }

  /** 화면-계약.md 의 응답 {@code actor} 는 사람이 읽는 이름이다. 기록은 회원번호만 지므로 조인으로 얻는다 (도메인 3.2). */
  @DisplayName("감사 로그에는 행위자 닉네임이 함께 온다.")
  @Test
  void findSliceCarriesActorNickname() {
    logged(BASE);

    List<ActedAuditLog> found = auditLogRepository.findSlice(query(null, 20));

    assertThat(found).hasSize(1);
    assertThat(found.get(0).actorNickname()).isEqualTo(ADMIN_NICKNAME);
  }

  @DisplayName("행위 종류와 대상이 그대로 읽힌다.")
  @Test
  void findSliceCarriesKindAndTarget() {
    anAuditLog().actorUserId(ADMIN_ID).kind(AuditKind.BLIND).targetId(31L).at(BASE).insert(jdbc);

    List<ActedAuditLog> found = auditLogRepository.findSlice(query(null, 20));

    AuditLog log = found.get(0).auditLog();
    assertThat(log.getKind()).isEqualTo(AuditKind.BLIND);
    assertThat(log.getTargetType()).isEqualTo(AuditTargetType.COMMENT);
    assertThat(log.getTargetId()).isEqualTo(31L);
  }

  @DisplayName("다음 페이지 유무를 알려고 한 건을 더 읽는다.")
  @Test
  void findSliceReadsOneMore() {
    logged(BASE.plusMinutes(1));
    logged(BASE.plusMinutes(2));
    logged(BASE.plusMinutes(3));

    assertThat(auditLogRepository.findSlice(query(null, 2))).hasSize(3);
  }

  /** 정렬 키가 같은 데이터를 일부러 만들어 경계를 본다 (테스트 컨벤션). 한 번의 처리에서 여러 줄이 잇달아 쌓이면 실제로 이렇게 된다. */
  @DisplayName("행위 시각이 같아도 id 가 순서를 정한다.")
  @Test
  void findSliceHasSameAt() {
    long first = logged(BASE);
    long second = logged(BASE);
    long third = logged(BASE);

    List<ActedAuditLog> page = auditLogRepository.findSlice(query(null, 2));
    assertThat(idsOf(page)).containsExactly(third, second, first);

    List<ActedAuditLog> next =
        auditLogRepository.findSlice(query(new AuditLogCursor(BASE, second), 2));

    assertThat(idsOf(next)).containsExactly(first);
  }

  @DisplayName("커서 다음부터 이어 읽고 앞 페이지를 다시 주지 않는다.")
  @Test
  void findSliceAfterCursor() {
    long oldest = logged(BASE.plusMinutes(1));
    long middle = logged(BASE.plusMinutes(2));
    long newest = logged(BASE.plusMinutes(3));

    List<ActedAuditLog> next =
        auditLogRepository.findSlice(query(new AuditLogCursor(BASE.plusMinutes(3), newest), 20));

    assertThat(idsOf(next)).containsExactly(middle, oldest);
  }

  private long logged(LocalDateTime at) {
    return anAuditLog().actorUserId(ADMIN_ID).at(at).insert(jdbc);
  }

  private static AuditLogListQuery query(AuditLogCursor cursor, int size) {
    return new AuditLogListQuery(cursor, size);
  }

  private static List<Long> idsOf(List<ActedAuditLog> found) {
    return found.stream().map(acted -> acted.auditLog().getId()).toList();
  }
}
