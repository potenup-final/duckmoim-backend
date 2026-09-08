package com.duckmoim.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.admin.domain.AuditKind;
import com.duckmoim.admin.domain.AuditLog;
import com.duckmoim.admin.domain.AuditLogListQuery;
import com.duckmoim.admin.domain.AuditTargetType;
import com.duckmoim.admin.infra.ActedAuditLog;
import com.duckmoim.admin.infra.AuditLogRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감사 로그의 기록 경로 (AD-05 · I-13).
 *
 * <p>실제 저장을 지나는 이유 — 이 티켓이 지켜야 하는 것이 「append-only <b>경로만</b> 제공」이라, 기록이 저장까지 닿는지와 그 밖의 경로가 없는지를 함께
 * 봐야 한다. 저장을 흉내 내면 둘 다 검증되지 않는다.
 *
 * <p>행위자는 V11 시드의 6 번('운영자')이다. V31 이 그를 화이트리스트에 넣는다.
 */
@SpringBootTest
@Transactional
class AuditLogRecorderTest {

  private static final long ADMIN_ID = 6L;
  private static final long TARGET_ID = 31L;

  @Autowired private AuditLogRecorder auditLogRecorder;
  @Autowired private AuditLogRepository auditLogRepository;

  @DisplayName("kind 다섯 가지가 모두 기록된다.")
  @ParameterizedTest(name = "{0}")
  @EnumSource(AuditKind.class)
  void record(AuditKind kind) {
    auditLogRecorder.record(ADMIN_ID, kind, TARGET_ID, "신고 5 처리 중");

    AuditLog saved = onlyOne().auditLog();
    assertThat(saved.getActorUserId()).isEqualTo(ADMIN_ID);
    assertThat(saved.getKind()).isEqualTo(kind);
    assertThat(saved.getTargetType()).isEqualTo(kind.targetType());
    assertThat(saved.getTargetId()).isEqualTo(TARGET_ID);
    assertThat(saved.getDetail()).isEqualTo("신고 5 처리 중");
  }

  @DisplayName("비밀 댓글 열람은 댓글을 가리키는 기록으로 남는다.")
  @Test
  void recordSecretRead() {
    auditLogRecorder.record(ADMIN_ID, AuditKind.SECRET_READ, TARGET_ID, "신고 5 처리 중 본문 열람");

    AuditLog saved = onlyOne().auditLog();
    assertThat(saved.getKind()).isEqualTo(AuditKind.SECRET_READ);
    assertThat(saved.getTargetType()).isEqualTo(AuditTargetType.COMMENT);
  }

  @DisplayName("상세는 없어도 기록된다.")
  @Test
  void recordWithoutDetail() {
    auditLogRecorder.record(ADMIN_ID, AuditKind.BLIND, TARGET_ID, null);

    assertThat(onlyOne().auditLog().getDetail()).isNull();
  }

  @DisplayName("행위 시각이 남는다.")
  @Test
  void recordCarriesTime() {
    auditLogRecorder.record(ADMIN_ID, AuditKind.PURGE, TARGET_ID, null);

    assertThat(onlyOne().auditLog().getAt()).isNotNull();
  }

  @DisplayName("기록한 순서와 상관없이 최신순으로 읽힌다.")
  @Test
  void recordThenRead() {
    auditLogRecorder.record(ADMIN_ID, AuditKind.SANCTION, TARGET_ID, "첫째");
    auditLogRecorder.record(ADMIN_ID, AuditKind.RELEASE, TARGET_ID, "둘째");

    List<ActedAuditLog> found = auditLogRepository.findSlice(new AuditLogListQuery(null, 20));

    assertThat(found).hasSize(2);
    assertThat(found.get(0).auditLog().getDetail()).isEqualTo("둘째");
    assertThat(found.get(0).actorNickname()).isEqualTo("운영자");
  }

  private ActedAuditLog onlyOne() {
    List<ActedAuditLog> found = auditLogRepository.findSlice(new AuditLogListQuery(null, 20));

    assertThat(found).hasSize(1);
    return found.get(0);
  }
}
