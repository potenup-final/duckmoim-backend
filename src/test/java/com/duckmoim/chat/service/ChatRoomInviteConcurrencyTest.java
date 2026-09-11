package com.duckmoim.chat.service;

import static com.duckmoim.companion.CommentFixture.aComment;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.common.exception.BusinessException;
import com.duckmoim.companion.service.CompanionPostCommandService;
import com.duckmoim.companion.service.CompanionPostWriteCommand;
import com.duckmoim.companion.service.WrittenCompanionPost;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 같은 방장이 같은 사람을 두 번 초대하는 경합 (CH-02 · CH-03).
 *
 * <p><b>{@code @Transactional} 이 없다.</b> 테스트 컨벤션이 <i>"별도 스레드는 테스트의 트랜잭션에 참여하지 않아서, 롤백을 걸면 테스트가 항상
 * 통과하는 상태가 된다"</i> 고 못박았다. 그래서 넣은 데이터를 손으로 지운다 ({@code ReportConcurrencyTest} 와 같은 자리).
 *
 * <p><b>사전 조회만으로는 뚫린다.</b> {@code ChatRoom.invite} 자바독이 적어 둔 대로, 두 요청이 같은 순간에 {@code memberOf} 를
 * 지나면 둘 다 통과한다. 실제 차단은 {@code uq_chat_room_member} 가 하고, {@code ChatRoomInviteService} 가 그 위반을 409
 * ({@code CHAT_ALREADY_MEMBER}) 로 옮긴다 — PR #103 리뷰가 지적한 자리다.
 */
@SpringBootTest
class ChatRoomInviteConcurrencyTest {

  private static final long HOST_ID = 7L;
  private static final long COMMENTER_ID = 11L;

  private static final BigDecimal LAT = new BigDecimal("37.5256381");
  private static final BigDecimal LNG = new BigDecimal("126.9289384");

  @Autowired private ChatRoomInviteService chatRoomInviteService;
  @Autowired private CompanionPostCommandService companionPostCommandService;
  @Autowired private JdbcTemplate jdbc;

  private long postId;

  /** 롤백이 없으니 손으로 지운다. 남기면 다음 테스트가 방 하나(I-16)에 걸린다. */
  @AfterEach
  void tearDown() {
    if (postId != 0) {
      jdbc.update(
          "DELETE FROM chat_room_member WHERE room_id IN"
              + " (SELECT id FROM chat_room WHERE post_id = ?)",
          postId);
      jdbc.update("DELETE FROM chat_room WHERE post_id = ?", postId);
      jdbc.update("DELETE FROM comment WHERE post_id = ?", postId);
      jdbc.update("DELETE FROM companion_post WHERE id = ?", postId);
    }
  }

  @DisplayName("같은 방장이 같은 사람을 동시에 초대하면 한 건만 성공한다.")
  @Test
  void invite_isConcurrent() throws Exception {
    WrittenCompanionPost written = companionPostCommandService.create(command());
    postId = written.id();
    aComment().postId(postId).authorId(COMMENTER_ID).insert(jdbc);

    int threads = 10;
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger accepted = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();
    AtomicReference<BusinessException> rejection = new AtomicReference<>();

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (int i = 0; i < threads; i++) {
        pool.submit(
            () -> {
              ready.countDown();
              try {
                start.await();
                chatRoomInviteService.invite(postId, COMMENTER_ID, HOST_ID);
                accepted.incrementAndGet();
              } catch (BusinessException e) {
                rejected.incrementAndGet();
                rejection.set(e);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
      }

      ready.await();
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    assertThat(accepted.get()).isEqualTo(1);
    assertThat(rejected.get()).isEqualTo(threads - 1);
    assertThat(rejection.get()).isNotNull();
    assertThat(rejection.get().getErrorCode()).isEqualTo(ChatErrorCode.CHAT_ALREADY_MEMBER);
    assertThat(storedMemberCount()).isEqualTo(1);
  }

  private int storedMemberCount() {
    List<Integer> counts =
        jdbc.queryForList(
            "SELECT COUNT(*) FROM chat_room_member m"
                + " JOIN chat_room r ON r.id = m.room_id"
                + " WHERE r.post_id = ? AND m.user_id = ?",
            Integer.class,
            postId,
            COMMENTER_ID);
    return counts.get(0);
  }

  private CompanionPostWriteCommand command() {
    return new CompanionPostWriteCommand(
        HOST_ID,
        "초대 경합 검증용 모집글",
        "본문",
        null,
        OffsetDateTime.parse("2026-10-01T09:00:00+09:00"),
        "합정역 1번 출구",
        LAT,
        LNG,
        null);
  }
}
