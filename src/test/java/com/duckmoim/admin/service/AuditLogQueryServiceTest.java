package com.duckmoim.admin.service;

import static com.duckmoim.admin.AuditLogFixture.anAuditLog;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.admin.domain.AuditKind;
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
 * 감사 로그 한 페이지의 조립 (AD-05).
 *
 * <p>커서 페이지네이션의 세 필드가 서로 맞물리는지를 본다 — {@code items} 는 {@code size} 만큼, {@code hasNext} 는 더 있는지,
 * {@code nextCursor} 는 이 페이지의 마지막을 가리키는지.
 */
@SpringBootTest
@Transactional
class AuditLogQueryServiceTest {

  private static final LocalDateTime BASE = LocalDateTime.of(2026, 9, 1, 0, 0);

  private static final long ADMIN_ID = 6L;

  @Autowired private AuditLogQueryService auditLogQueryService;
  @Autowired private JdbcTemplate jdbc;

  @DisplayName("감사 로그를 최신순으로 한 페이지 읽는다.")
  @Test
  void findAuditLogs() {
    long oldest = logged(BASE.plusMinutes(1));
    long newest = logged(BASE.plusMinutes(2));

    AuditLogSlice slice = auditLogQueryService.findAuditLogs(query(null, 20));

    assertThat(idsOf(slice)).containsExactly(newest, oldest);
  }

  @DisplayName("마지막 페이지는 nextCursor 가 null 이고 hasNext 가 false 다.")
  @Test
  void findAuditLogsOnLastPage() {
    logged(BASE.plusMinutes(1));
    logged(BASE.plusMinutes(2));

    AuditLogSlice slice = auditLogQueryService.findAuditLogs(query(null, 20));

    assertThat(slice.hasNext()).isFalse();
    assertThat(slice.nextCursor()).isNull();
  }

  @DisplayName("더 읽을 것이 있으면 nextCursor 가 이 페이지의 마지막을 가리킨다.")
  @Test
  void findAuditLogsHasNext() {
    logged(BASE.plusMinutes(1));
    long second = logged(BASE.plusMinutes(2));
    logged(BASE.plusMinutes(3));

    AuditLogSlice slice = auditLogQueryService.findAuditLogs(query(null, 2));

    assertThat(slice.items()).hasSize(2);
    assertThat(slice.hasNext()).isTrue();
    assertThat(slice.nextCursor()).isEqualTo(new AuditLogCursor(BASE.plusMinutes(2), second));
  }

  @DisplayName("커서로 이어 읽으면 이전 페이지와 겹치지 않는다.")
  @Test
  void findAuditLogsAfterCursor() {
    long oldest = logged(BASE.plusMinutes(1));
    logged(BASE.plusMinutes(2));
    logged(BASE.plusMinutes(3));

    AuditLogSlice first = auditLogQueryService.findAuditLogs(query(null, 2));
    AuditLogSlice next = auditLogQueryService.findAuditLogs(query(first.nextCursor(), 2));

    assertThat(idsOf(next)).containsExactly(oldest);
    assertThat(idsOf(next)).doesNotContainAnyElementsOf(idsOf(first));
  }

  @DisplayName("행위자 닉네임이 actor 로 실린다.")
  @Test
  void findAuditLogsCarriesActor() {
    logged(BASE);

    AuditLogView view = auditLogQueryService.findAuditLogs(query(null, 20)).items().get(0);

    assertThat(view.actor()).isEqualTo("운영자");
  }

  @DisplayName("행위 종류와 대상이 그대로 실린다.")
  @Test
  void findAuditLogsCarriesKindAndTarget() {
    anAuditLog().actorUserId(ADMIN_ID).kind(AuditKind.SANCTION).targetId(4L).at(BASE).insert(jdbc);

    AuditLogView view = auditLogQueryService.findAuditLogs(query(null, 20)).items().get(0);

    assertThat(view.kind()).isEqualTo(AuditKind.SANCTION);
    assertThat(view.targetType()).isEqualTo(AuditTargetType.USER);
    assertThat(view.targetId()).isEqualTo(4L);
    assertThat(view.at()).isEqualTo(BASE);
  }

  @DisplayName("기록이 없으면 빈 페이지다.")
  @Test
  void findAuditLogsWhenEmpty() {
    AuditLogSlice slice = auditLogQueryService.findAuditLogs(query(null, 20));

    assertThat(slice.items()).isEmpty();
    assertThat(slice.hasNext()).isFalse();
    assertThat(slice.nextCursor()).isNull();
  }

  private long logged(LocalDateTime at) {
    return anAuditLog().actorUserId(ADMIN_ID).at(at).insert(jdbc);
  }

  private static AuditLogListQuery query(AuditLogCursor cursor, int size) {
    return new AuditLogListQuery(cursor, size);
  }

  private static List<Long> idsOf(AuditLogSlice slice) {
    return slice.items().stream().map(AuditLogView::id).toList();
  }
}
