package com.duckmoim.chat.infra;

import static com.duckmoim.companion.CompanionPostFixture.aCompanionPost;
import static com.duckmoim.identity.UserFixture.aUser;
import static org.assertj.core.api.Assertions.assertThat;

import com.duckmoim.chat.domain.ChatRoom;
import com.duckmoim.identity.domain.SignupStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 내가 속한 방 목록 (CH-05).
 *
 * <p><b>{@code @Transactional} 이 없다.</b> {@code ChatRoomRepositoryTest} 와 같은 이유 — 별도 스레드 검증은 아니지만,
 * 이 클래스도 손으로 넣고 손으로 지우는 관례를 맞춘다.
 */
@SpringBootTest
class ChatRoomQueryRepositoryTest {

  private static final long HOST_ID = 1L;
  private static final long GUEST_ID = 2L;
  private static final long STRANGER_ID = 3L;

  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private JdbcTemplate jdbc;

  @AfterEach
  void tearDown() {
    jdbc.update(
        "DELETE FROM chat_room_member WHERE room_id IN"
            + " (SELECT id FROM chat_room WHERE post_id IN"
            + " (SELECT id FROM companion_post WHERE title LIKE 'CH-05 픽스처%'))");
    jdbc.update(
        "DELETE FROM chat_room WHERE post_id IN"
            + " (SELECT id FROM companion_post WHERE title LIKE 'CH-05 픽스처%')");
    jdbc.update("DELETE FROM companion_post WHERE title LIKE 'CH-05 픽스처%'");
  }

  @DisplayName("내가 속한 방 목록을 만남시각 순으로 반환한다.")
  @Test
  void findSummariesForMember() {
    // given
    LocalDateTime meetAt = LocalDateTime.of(2026, 10, 1, 9, 0);
    long postId = aCompanionPost().title("CH-05 픽스처 방").meetAt(meetAt).insert(jdbc);
    ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.openFor(postId, HOST_ID));
    room.invite(GUEST_ID);
    chatRoomRepository.saveAndFlush(room);

    // when
    ChatRoomSummary summary =
        chatRoomRepository.findSummariesForMember(HOST_ID).stream()
            .filter(s -> s.postId().equals(postId))
            .findFirst()
            .orElseThrow();

    // then
    assertThat(summary.postTitle()).isEqualTo("CH-05 픽스처 방");
    assertThat(summary.meetAt()).isEqualTo(meetAt);
    assertThat(summary.memberCount()).isEqualTo(2);
  }

  @DisplayName("나간 방은 목록에 없다.")
  @Test
  void findSummariesForMember_excludesLeftRoom() {
    // given
    long postId = aCompanionPost().title("CH-05 픽스처 나간방").insert(jdbc);
    ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.openFor(postId, HOST_ID));
    room.invite(GUEST_ID);
    chatRoomRepository.saveAndFlush(room);
    jdbc.update(
        "UPDATE chat_room_member SET left_at = UTC_TIMESTAMP(6)"
            + " WHERE room_id = ? AND user_id = ?",
        room.getId(),
        GUEST_ID);

    // when
    var summaries = chatRoomRepository.findSummariesForMember(GUEST_ID);

    // then
    assertThat(summaries).extracting(ChatRoomSummary::postId).doesNotContain(postId);
  }

  @DisplayName("멤버가 방장뿐인 방도 목록에 있다.")
  @Test
  void findSummariesForMember_hostOnlyRoom() {
    // given
    long postId = aCompanionPost().title("CH-05 픽스처 방장뿐").insert(jdbc);
    chatRoomRepository.saveAndFlush(ChatRoom.openFor(postId, HOST_ID));

    // when
    ChatRoomSummary summary =
        chatRoomRepository.findSummariesForMember(HOST_ID).stream()
            .filter(s -> s.postId().equals(postId))
            .findFirst()
            .orElseThrow();

    // then
    assertThat(summary.memberCount()).isEqualTo(1);
  }

  @DisplayName("속하지 않은 방은 목록에 없다.")
  @Test
  void findSummariesForMember_excludesRoomsNotJoined() {
    // given
    long postId = aCompanionPost().title("CH-05 픽스처 무관계자").insert(jdbc);
    chatRoomRepository.saveAndFlush(ChatRoom.openFor(postId, HOST_ID));

    // when
    var summaries = chatRoomRepository.findSummariesForMember(STRANGER_ID);

    // then
    assertThat(summaries).extracting(ChatRoomSummary::postId).doesNotContain(postId);
  }

  @DisplayName("방 상세의 멤버 목록에 유저 정보가 함께 실린다.")
  @Test
  void findMembersOf() {
    // given
    long hostUserId = aUser().nickname("방장픽스처").insert(jdbc);
    long postId = aCompanionPost().title("CH-06 픽스처 방").insert(jdbc);
    ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.openFor(postId, hostUserId));

    // when
    var members = chatRoomRepository.findMembersOf(room.getId());

    // then
    assertThat(members)
        .hasSize(1)
        .first()
        .satisfies(
            member -> {
              assertThat(member.userId()).isEqualTo(hostUserId);
              assertThat(member.nickname()).isEqualTo("방장픽스처");
            });
  }

  @DisplayName("나간 멤버는 방 상세 목록에 없다.")
  @Test
  void findMembersOf_excludesLeftMember() {
    // given
    long hostUserId = aUser().insert(jdbc);
    long guestUserId = aUser().insert(jdbc);
    long postId = aCompanionPost().title("CH-06 픽스처 나간멤버").insert(jdbc);
    ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.openFor(postId, hostUserId));
    room.invite(guestUserId);
    chatRoomRepository.saveAndFlush(room);
    jdbc.update(
        "UPDATE chat_room_member SET left_at = UTC_TIMESTAMP(6)"
            + " WHERE room_id = ? AND user_id = ?",
        room.getId(),
        guestUserId);

    // when
    var members = chatRoomRepository.findMembersOf(room.getId());

    // then
    assertThat(members).extracting(AuthoredChatRoomMember::userId).containsExactly(hostUserId);
  }

  @DisplayName("탈퇴한 멤버도 목록에 남고 상태만 실린다.")
  @Test
  void findMembersOf_includesWithdrawnMember() {
    // given
    long withdrawnUserId = aUser().status(SignupStatus.WITHDRAWN).insert(jdbc);
    long postId = aCompanionPost().title("CH-06 픽스처 탈퇴멤버").insert(jdbc);
    ChatRoom room = chatRoomRepository.saveAndFlush(ChatRoom.openFor(postId, withdrawnUserId));

    // when
    var members = chatRoomRepository.findMembersOf(room.getId());

    // then
    assertThat(members)
        .first()
        .satisfies(member -> assertThat(member.status()).isEqualTo(SignupStatus.WITHDRAWN));
  }
}
