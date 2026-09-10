package com.duckmoim.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 방 개설의 검증 기준 중 도메인이 지는 것 (CH-01).
 *
 * <p>「방은 모집글 하나에 하나다」(I-16)는 여기 없다. 도메인-모델링.md 「5. 불변식」이 이중 방어를 유니크 제약으로 정해 DB 가 지므로 통합 테스트에서 본다
 * ({@code ChatRoomRepositoryTest}).
 */
class ChatRoomTest {

  private static final long POST_ID = 1L;
  private static final long HOST_ID = 7L;

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

  @DisplayName("멤버 목록을 밖에서 고칠 수 없다.")
  @Test
  void membersAreUnmodifiable() {
    List<ChatRoomMember> members = ChatRoom.openFor(POST_ID, HOST_ID).getMembers();

    assertThatThrownBy(() -> members.remove(0)).isInstanceOf(UnsupportedOperationException.class);
  }
}
