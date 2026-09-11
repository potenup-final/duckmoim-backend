package com.duckmoim.companion.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 아웃박스 행이 댓글 작성과 <b>같은 트랜잭션</b>에 쌓이는지 (NT-01 · I-25).
 *
 * <p>이 티켓에서 고정할 수 있는 검증 기준의 절반이다. 명세는 <i>「알림 채널을 죽인 상태에서 댓글 작성 성공률 100%」</i> 인데 죽일 채널이 아직 없다 (NT-02
 * · NT-13). 지금 보증할 수 있는 것은 발송을 부르는 코드가 없다는 것과, 행과 댓글의 운명이 하나라는 것이다.
 *
 * <p><b>클래스에 {@code @Transactional} 을 붙이지 않는다.</b> {@code SanctionAuditTransactionTest} 와 같은 이유다 —
 * 붙이면 테스트가 통째로 롤백되어 「부르는 쪽이 롤백하면 행도 사라진다」를 볼 수 없고, 무엇이 롤백을 일으켰는지 구분되지 않는다.
 *
 * <p><b>이 검사가 지키는 것.</b> 발행기가 {@code REQUIRES_NEW} 로 떨어지거나 커밋 뒤로 밀리면, 없는 댓글의 알림이 발송되거나 (앞 경우) 댓글은
 * 있는데 알림이 유실된다 (뒤 경우). 둘 다 사용자에게 보이고 되돌릴 수 없다.
 *
 * <p><b>커밋되는 경우를 따로 두지 않았다.</b> 발행기가 {@code MANDATORY} 로 부르는 쪽 트랜잭션에 참여하므로 롤백이 함께 되는 것이 곧 커밋도 함께
 * 된다는 뜻이고, 행이 쌓이는 것 자체는 {@code CommentNotificationOutboxTest} 가 본다. 커밋하는 검사를 두면 공유 DB 에 행이 남는다.
 */
@SpringBootTest
class CommentWriteOutboxTransactionTest {

  private static final long HOST_ID = 1L;
  private static final long WRITER_ID = 8L;

  @Autowired private CommentCommandService commentCommandService;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbc;

  private long openPostId;

  @BeforeEach
  void setUp() {
    openPostId = aCompanionPost().hostId(HOST_ID).insert(jdbc);
  }

  @DisplayName("댓글 작성이 롤백되면 아웃박스 행도 남지 않는다.")
  @Test
  void write_rollsBackOutboxTogether() {
    // when
    transactionTemplate.executeWithoutResult(
        status -> {
          commentCommandService.write(
              new CommentWriteCommand(openPostId, WRITER_ID, null, "저 갈게요!", false));

          status.setRollbackOnly();
        });

    // then — 댓글도 행도 없다. 하나만 남으면 둘의 운명이 갈린 것이다
    assertThat(countComments()).isZero();
    assertThat(countOutboxRows()).isZero();
  }

  private long countComments() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM comment WHERE post_id = ?", Long.class, openPostId);
  }

  /** 자기 모집글의 행만 센다 — 표 전체를 보면 다른 테스트가 커밋해 둔 행이 함께 잡힌다. */
  private long countOutboxRows() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM notification_outbox WHERE post_id = ?", Long.class, openPostId);
  }
}
