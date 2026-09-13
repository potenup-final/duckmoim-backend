package com.duckmoim.chat.service;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.chat.infra.ChatRoomRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내가 속한 방 목록의 검증 기준 (CH-05).
 *
 * <p>실제 MySQL 로 돈다 — {@code ChatRoomQueryRepositoryTest} 가 이미 쿼리 자체를 검증했으니 여기는 service 가 그 결과를 view
 * 로 옮기는 조립만 본다. {@code @Transactional} 로 시드를 되돌린다 ({@code CompanionPostQueryServiceTest} 와 같은 방식).
 */
@SpringBootTest
@Transactional
class ChatRoomListQueryServiceTest {

  private static final long HOST_ID = 1L;
  private static final long GUEST_ID = 2L;

  @Autowired private ChatRoomListQueryService chatRoomListQueryService;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private JdbcTemplate jdbc;

  @DisplayName("내가 속한 방 목록을 반환한다.")
  @Test
  void findRooms() {
    // given
    LocalDateTime meetAt = LocalDateTime.of(2026, 10, 1, 9, 0);
    long postId = aCompanionPost().title("CH-05 서비스 픽스처").meetAt(meetAt).insert(jdbc);
    ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.openFor(postId, HOST_ID));
    room.invite(GUEST_ID);
    chatRoomRepository.saveAndFlush(room);

    // when
    List<ChatRoomSummaryView> rooms = chatRoomListQueryService.findRooms(HOST_ID);

    // then
    assertThat(rooms)
        .filteredOn(view -> view.postId().equals(postId))
        .singleElement()
        .satisfies(
            view -> {
              assertThat(view.postTitle()).isEqualTo("CH-05 서비스 픽스처");
              assertThat(view.meetAt()).isEqualTo(meetAt);
              assertThat(view.memberCount()).isEqualTo(2);
            });
  }

  @DisplayName("속하지 않은 사람에게는 빈 목록이다.")
  @Test
  void findRooms_empty() {
    assertThat(chatRoomListQueryService.findRooms(999_999L)).isEmpty();
  }
}
