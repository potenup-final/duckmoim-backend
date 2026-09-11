package com.duckmoim.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.common.exception.BusinessException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 방 개설과 초대의 검증 기준 중 도메인이 지는 것 (CH-01 · CH-02 · CH-02a · CH-03).
 *
 * <p><b>초대의 셋만 여기 있다</b> — 이미 멤버, 나간 사람, 인원 상한. 방장인지(403)와 댓글을 썼는지(400)는 모집글과 댓글이 아는 사실이라 {@code
 * ChatRoomInviteServiceTest} 가 본다.
 *
 * <p>「방은 모집글 하나에 하나다」(I-16)는 여기 없다. 도메인-모델링.md 「5. 불변식」이 이중 방어를 유니크 제약으로 정해 DB 가 지므로 통합 테스트에서 본다
 * ({@code ChatRoomRepositoryTest}).
 */
class ChatRoomTest {

  private static final long POST_ID = 1L;
  private static final long HOST_ID = 7L;
  private static final long GUEST_ID = 11L;

  @DisplayName("모집글의 방을 열면 그 모집글을 참조한다.")
  @Test
  void openFor() {
    ChatRoom room = ChatRoom.openFor(POST_ID, HOST_ID);

    assertThat(room.getPostId()).isEqualTo(POST_ID);
  }

  @DisplayName("새로 연 방의 멤버는 방장 하나다.")
  @Test
  void openForStartsWithHostAlone() {
    ChatRoom room = ChatRoom.openFor(POST_ID, HOST_ID);

    assertThat(room.currentMembers())
        .extracting(ChatRoomMember::getUserId)
        .containsExactly(HOST_ID);
  }

  @DisplayName("방장은 들어온 시각을 갖고 나간 시각은 비어 있다.")
  @Test
  void openForJoinsHost() {
    ChatRoom room = ChatRoom.openFor(POST_ID, HOST_ID);

    ChatRoomMember host = room.currentMembers().get(0);
    assertThat(host.getJoinedAt()).isNotNull();
    assertThat(host.getLeftAt()).isNull();
    assertThat(host.isJoined()).isTrue();
  }

  @DisplayName("초대하면 곧바로 멤버가 된다. 수락 단계가 없다.")
  @Test
  void invite() {
    ChatRoom room = ChatRoom.openFor(POST_ID, HOST_ID);

    room.invite(GUEST_ID);

    assertThat(room.currentMembers())
        .extracting(ChatRoomMember::getUserId)
        .containsExactly(HOST_ID, GUEST_ID);
  }

  @DisplayName("이미 멤버인 사람을 다시 초대하면 409 다.")
  @Test
  void inviteAlreadyMember() {
    ChatRoom room = ChatRoom.openFor(POST_ID, HOST_ID);
    room.invite(GUEST_ID);

    assertThatThrownBy(() -> room.invite(GUEST_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ALREADY_MEMBER);
  }

  /** 방장도 멤버라 (CH-01) 자기 자신을 부르면 같은 자리에 걸린다. */
  @DisplayName("방장이 자기 자신을 초대하면 409 다.")
  @Test
  void inviteHost() {
    ChatRoom room = ChatRoom.openFor(POST_ID, HOST_ID);

    assertThatThrownBy(() -> room.invite(HOST_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ALREADY_MEMBER);
  }

  /**
   * CH-02a. 나간 사람의 행이 남아 있어야 성립하는 규칙이라, 퇴장(CH-04)이 행을 지우는 순간 이 검사가 조용히 통과하게 된다.
   *
   * <p>퇴장 명령이 아직 없어 {@code leftAt} 을 리플렉션으로 채운다. CH-04 가 들어오면 그 명령으로 바꾼다.
   */
  @DisplayName("스스로 나간 사람은 다시 초대할 수 없다.")
  @Test
  void inviteLeftMember() {
    ChatRoom room = ChatRoom.openFor(POST_ID, HOST_ID);
    room.invite(GUEST_ID);
    markLeft(room, GUEST_ID);

    assertThatThrownBy(() -> room.invite(GUEST_ID))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_MEMBER_LEFT);
  }

  @DisplayName("상한 100 을 방장까지 세므로 초대로 채울 수 있는 자리는 99 다.")
  @Test
  void inviteUpToLimit() {
    ChatRoom room = fullRoom();

    assertThat(room.currentMembers()).hasSize(ChatRoom.MEMBER_LIMIT);
  }

  @DisplayName("상한을 넘기는 초대는 409 다.")
  @Test
  void inviteOverLimit() {
    ChatRoom room = fullRoom();

    assertThatThrownBy(() -> room.invite(9999L))
        .isInstanceOf(BusinessException.class)
        .extracting("errorCode")
        .isEqualTo(ChatErrorCode.CHAT_ROOM_MEMBER_LIMIT_EXCEEDED);
  }

  /**
   * 나간 사람은 자리를 차지하지 않는다.
   *
   * <p>I-17 이 「방 멤버는 100명을 넘지 않는다」 이고 나간 사람은 멤버가 아니다 (CH-18). 행으로 세면 방이 한 번 찬 뒤로는 아무도 못 들어오는데, 그것은
   * 상한이 아니라 누적 인원 제한이 된다.
   */
  @DisplayName("나간 사람의 자리는 상한에서 빠진다.")
  @Test
  void leftMemberFreesSeat() {
    ChatRoom room = fullRoom();
    markLeft(room, GUEST_ID);

    room.invite(9999L);

    assertThat(room.currentMembers()).hasSize(ChatRoom.MEMBER_LIMIT);
  }

  /** 방장 하나 + 초대 99 = 100. GUEST_ID 가 그중 첫 초대다. */
  private static ChatRoom fullRoom() {
    ChatRoom room = ChatRoom.openFor(POST_ID, HOST_ID);
    IntStream.range(0, ChatRoom.MEMBER_LIMIT - 1).forEach(seat -> room.invite(GUEST_ID + seat));

    return room;
  }

  private static void markLeft(ChatRoom room, long userId) {
    ChatRoomMember member =
        room.getMembers().stream()
            .filter(each -> each.getUserId() == userId)
            .findFirst()
            .orElseThrow();

    ReflectionTestUtils.setField(member, "leftAt", LocalDateTime.now(ZoneOffset.UTC));
  }

  @DisplayName("멤버 목록을 밖에서 고칠 수 없다.")
  @Test
  void membersAreUnmodifiable() {
    List<ChatRoomMember> members = ChatRoom.openFor(POST_ID, HOST_ID).getMembers();

    assertThatThrownBy(() -> members.remove(0)).isInstanceOf(UnsupportedOperationException.class);
  }
}
