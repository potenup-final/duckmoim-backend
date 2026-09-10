package com.duckmoim.chat.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.domain.ChatRoomMember;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 모집글 하나에 방 하나 (I-16).
 *
 * <p><b>실제 MySQL 로 돈다.</b> 도메인-모델링.md 「5. 불변식」이 I-16 의 이중 방어를 유니크 제약으로 정해 <b>이 불변식의 방어선이 DB
 * 하나</b>다. 단위 테스트로는 잡히지 않는다.
 *
 * <p><b>{@code @Transactional} 이 없다.</b> 테스트 컨벤션이 <i>"별도 스레드는 테스트의 트랜잭션에 참여하지 않아서, 롤백을 걸면 테스트가 항상
 * 통과하는 상태가 된다"</i> 고 못박았다. 그래서 넣은 행을 손으로 지운다.
 *
 * <p><b>모집글 행을 만들지 않는다.</b> 애그리게이트 밖은 ID 로만 참조하고 FK 를 걸지 않으므로 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」) 방을
 * 넣는 데 모집글이 실재할 필요가 없다. 시드 모집글 번호를 피해 큰 번호를 쓴다.
 */
@SpringBootTest
class ChatRoomRepositoryTest {

  private static final long POST_ID = 900_001L;
  private static final long HOST_ID = 1L;

  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private JdbcTemplate jdbc;

  /** 롤백이 없으니 손으로 지운다. 멤버가 먼저다 — 방을 먼저 지우면 멤버 행이 떠 있는 순간이 생긴다. */
  @AfterEach
  void tearDown() {
    jdbc.update(
        "DELETE FROM chat_room_member WHERE room_id IN (SELECT id FROM chat_room WHERE post_id = ?)",
        POST_ID);
    jdbc.update("DELETE FROM chat_room WHERE post_id = ?", POST_ID);
  }

  @DisplayName("모집글 번호로 그 글의 채팅방을 찾는다.")
  @Test
  void findByPostId() {
    // given
    chatRoomRepository.saveAndFlush(ChatRoom.openFor(POST_ID, HOST_ID));

    // when
    ChatRoom found = chatRoomRepository.findByPostId(POST_ID).orElseThrow();

    // then
    assertThat(found.getPostId()).isEqualTo(POST_ID);
    assertThat(found.currentMembers())
        .extracting(ChatRoomMember::getUserId)
        .containsExactly(HOST_ID);
  }

  @DisplayName("방이 없는 모집글 번호로 찾으면 비어 있다.")
  @Test
  void findByPostId_roomDoesNotExist() {
    assertThat(chatRoomRepository.findByPostId(POST_ID)).isEmpty();
  }

  /**
   * I-16 의 이중 방어다 — 유니크 제약({@code post_id}).
   *
   * <p><b>경쟁이 생길 수 있는 자리가 실재한다.</b> 방을 만드는 방아쇠가 모집글 작성 하나라 정상 경로에서는 한 번뿐이지만, CH-01a 의 백필과 작성이 배포
   * 순간에 겹칠 수 있고 재시도도 같은 모양이다. 그때 방이 둘이 되면 조회가 어느 쪽을 집는지에 따라 대화가 갈린다.
   *
   * <p>스레드 둘 중 하나만 성공하고 나머지는 제약 위반으로 실패한다. <b>세는 것은 남은 방의 수</b>다 — 예외의 종류가 아니라 행이 하나인 것이 불변식이다.
   */
  @DisplayName("같은 모집글로 방을 동시에 두 번 열어도 방은 하나다.")
  @Test
  void openingSameRoomTwiceLeavesOneRow() throws Exception {
    // given
    CountDownLatch start = new CountDownLatch(1);

    // when
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Boolean>> futures =
          List.of(openingRun(start), openingRun(start)).stream().map(pool::submit).toList();

      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

      long succeeded = 0;
      for (Future<Boolean> future : futures) {
        succeeded += Boolean.TRUE.equals(future.get()) ? 1 : 0;
      }
      assertThat(succeeded).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }

    // then
    assertThat(roomCount()).isEqualTo(1);
  }

  private Callable<Boolean> openingRun(CountDownLatch start) {
    return () -> {
      start.await();
      try {
        chatRoomRepository.saveAndFlush(ChatRoom.openFor(POST_ID, HOST_ID));
        return true;
      } catch (RuntimeException e) {
        return false;
      }
    };
  }

  private int roomCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM chat_room WHERE post_id = ?", Integer.class, POST_ID);
  }
}
