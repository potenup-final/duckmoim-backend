package com.duckmoim.chat.domain;

import com.duckmoim.common.domain.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 모집글 하나에 딸린 채팅방 (CH-01).
 *
 * <p><b>모집글 하나에 방 하나다</b> (I-16). 도메인-모델링.md 「5. 불변식」이 검증 위치를 「생성 시」로 정했고 이중 방어가 유니크 제약({@code
 * post_id})이라, 여기서 세는 것이 아니라 DB 가 두 번째를 거부한다.
 *
 * <p><b>상태를 저장하지 않는다.</b> 도메인-모델링.md 「{@code ChatRoom} ※ 2차」가 <i>"쓸 수 있는지 여부는 모집글의 만남시각에서 계산한다"</i>
 * 고 정했다 (CH-08). 그래서 이 클래스에 열림·닫힘도, 읽기 전용 플래그도 없다.
 *
 * <p><b>방장을 갖지 않는다.</b> 도메인-모델링.md 「3.1 경계와 트랜잭션 범위」의 경계 안 목록이 「멤버 목록, 멤버별 마지막 읽은 지점, 모집글 참조」 셋이고
 * 방장이 없다 — 어느 모집글의 방장인지는 그 글이 아는 사실이다. 방장만 할 수 있는 일(초대 CH-02 · 퇴장 금지 CH-04)은 모집글을 읽어 판정한다.
 *
 * <p><b>멤버는 이 애그리게이트 안이고 {@code Message} 는 밖이다</b> (도메인-모델링.md 「3.1 경계와 트랜잭션 범위」). 그래서 멤버만 객체로 붙들고
 * 메시지는 {@code roomId} 로 참조된다.
 */
@Entity
@Table(name = "chat_room")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom extends BaseEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  // 애그리게이트 밖은 ID 로만 참조한다 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」).
  @Column(name = "post_id", nullable = false)
  private Long postId;

  @OneToMany(mappedBy = "room", cascade = CascadeType.ALL)
  private List<ChatRoomMember> members = new ArrayList<>();

  private ChatRoom(Long postId) {
    this.postId = postId;
  }

  /**
   * 모집글의 방을 연다 (CH-01).
   *
   * <p><b>방장이 유일한 멤버로 시작한다.</b> 아무도 초대받지 않은 방도 목록과 상세에 그대로 나오므로 (CH-05 · CH-06) 「멤버가 없는 방」 이라는 상태를
   * 만들지 않는다.
   */
  public static ChatRoom openFor(Long postId, Long hostId) {
    ChatRoom room = new ChatRoom(postId);
    room.members.add(ChatRoomMember.joining(room, hostId));

    return room;
  }

  /** 나가지 않은 멤버 (CH-18). 나간 사람의 행은 남지만 멤버는 아니다. */
  public List<ChatRoomMember> currentMembers() {
    return members.stream().filter(ChatRoomMember::isJoined).toList();
  }

  public List<ChatRoomMember> getMembers() {
    return Collections.unmodifiableList(members);
  }
}
