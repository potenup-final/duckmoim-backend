package com.duckmoim.chat.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 기존 모집글의 방 (CH-01a).
 *
 * <p><b>마이그레이션 파일을 읽어서 다시 실행한다.</b> Flyway 는 적용된 파일을 두 번 돌리지 않으므로 검증 기준의 「마이그레이션을 두 번 돌려도 방이 하나」를
 * 확인할 방법이 그것뿐이다. SQL 을 테스트에 옮겨 적으면 <b>배포되는 것과 검증되는 것이 갈린다</b> — 파일을 고치고 테스트를 안 고쳐도 초록불이 된다.
 *
 * <p><b>{@code @Transactional} 이 없다.</b> 멱등성은 커밋된 상태를 다시 만나야 검증된다. 대신 이 테스트는 행을 넣지 않는다 — 두 번째 실행이
 * 아무 일도 하지 않는 것이 곧 단언이라, 성공하면 지울 것이 없다.
 *
 * <p><b>시드 모집글 셋으로 검증한다.</b> {@code V21} 의 세 글이 V700 · V701 보다 먼저 적용되어 「1차에 쓴 모집글」 그 자체다 — 하나는
 * {@code CLOSED} 이고 하나는 행사를 안 골랐다. 이 티켓이 상태로도 행사 유무로도 만남시각으로도 거르지 않으므로 셋 다 방이 있어야 한다.
 *
 * <p><b>모집글 전체를 세지 않는 이유가 있다.</b> 다른 테스트가 {@code JdbcTemplate} 으로 모집글을 직접 넣는다 — CH-01 의 방아쇠를 지나지
 * 않으므로 그 글에는 방이 없는 것이 정상이다. 전체를 세면 이 테스트가 남의 픽스처 정리 시점에 매달린다.
 */
@SpringBootTest
class ChatRoomBackfillMigrationTest {

  private static final String BACKFILL = "db/migration/V701__backfill_chat_room.sql";

  /** V21 이 넣은 세 글. 하나는 CLOSED 이고 하나는 행사를 고르지 않았다. */
  private static final List<Long> SEED_POST_IDS = List.of(1L, 2L, 3L);

  @Autowired private JdbcTemplate jdbc;

  @DisplayName("1차에 쓴 모집글에도 채팅방이 있다.")
  @Test
  void backfillOpensRoomForEverySeedPost() {
    assertThat(SEED_POST_IDS).allSatisfy(postId -> assertThat(roomCountOf(postId)).isEqualTo(1));
  }

  @DisplayName("백필로 생긴 방의 멤버는 그 모집글의 방장 하나다.")
  @Test
  void backfillJoinsHostAlone() {
    assertThat(SEED_POST_IDS)
        .allSatisfy(
            postId -> {
              List<Map<String, Object>> members = membersOf(postId);

              assertThat(members).hasSize(1);
              assertThat(members.get(0).get("user_id")).isEqualTo(hostIdOf(postId));
              assertThat(members.get(0).get("left_at")).isNull();
            });
  }

  /**
   * CH-01a 의 검증 기준이다 — 「마이그레이션을 두 번 돌려도 방이 하나」.
   *
   * <p>마이그레이션이 두 문장 사이에서 실패해 다시 돌 수 있고, 배포 순간에 새 글이 들어와 CH-01 이 이미 만든 방과 겹칠 수도 있다. 그때 방이 둘이 되면 조회가
   * 어느 쪽을 집는지에 따라 대화가 갈린다.
   */
  @DisplayName("백필을 다시 실행해도 방과 멤버가 늘지 않는다.")
  @Test
  void backfillIsIdempotent() throws IOException {
    // given
    int roomsBefore = seedRoomCount();
    int membersBefore = seedMemberCount();

    // when
    backfillAgain();

    // then
    assertThat(seedRoomCount()).isEqualTo(roomsBefore);
    assertThat(seedMemberCount()).isEqualTo(membersBefore);
  }

  /** 배포되는 파일 그대로 실행한다. 주석 줄과 빈 문장을 걸러 낸다. */
  private void backfillAgain() throws IOException {
    String sql = new ClassPathResource(BACKFILL).getContentAsString(StandardCharsets.UTF_8);

    Arrays.stream(sql.split(";"))
        .map(ChatRoomBackfillMigrationTest::withoutCommentLines)
        .filter(statement -> !statement.isBlank())
        .forEach(jdbc::execute);
  }

  private static String withoutCommentLines(String statement) {
    return statement
        .lines()
        .filter(line -> !line.stripLeading().startsWith("--"))
        .collect(Collectors.joining("\n"))
        .strip();
  }

  /**
   * 시드 세 글의 방만 센다.
   *
   * <p><b>표 전체를 세면 이 테스트가 남의 픽스처에 매달린다.</b> 방 없는 모집글이 한 건이라도 커밋된 채 남아 있으면 (트랜잭션 없이 도는 테스트가
   * {@code @AfterEach} 전에 죽는 경우) 백필 재실행이 그 글에 방을 만들어 수가 늘어난다. <b>백필이 고장나서가 아니라 정상 동작해서 빨간불이 되는
   * 모양</b>이라 원인 추적이 특히 나쁘고, 남의 글에 방을 남기는 부작용까지 있다.
   */
  private int seedRoomCount() {
    return countIn("SELECT COUNT(*) FROM chat_room WHERE post_id IN (?, ?, ?)");
  }

  private int seedMemberCount() {
    return countIn(
        """
        SELECT COUNT(*)
        FROM chat_room_member m
                 JOIN chat_room r ON r.id = m.room_id
        WHERE r.post_id IN (?, ?, ?)
        """);
  }

  private int countIn(String sql) {
    return jdbc.queryForObject(sql, Integer.class, SEED_POST_IDS.toArray());
  }

  private int roomCountOf(long postId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM chat_room WHERE post_id = ?", Integer.class, postId);
  }

  private List<Map<String, Object>> membersOf(long postId) {
    return jdbc.queryForList(
        """
        SELECT m.user_id, m.left_at
        FROM chat_room_member m
                 JOIN chat_room r ON r.id = m.room_id
        WHERE r.post_id = ?
        """,
        postId);
  }

  private Long hostIdOf(long postId) {
    return jdbc.queryForObject(
        "SELECT host_id FROM companion_post WHERE id = ?", Long.class, postId);
  }
}
