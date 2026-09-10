package com.duckmoim.chat.domain;

import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.common.domain.BaseEntity;
import com.duckmoim.common.exception.BusinessException;
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
import java.util.Optional;
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

  /**
   * 방 인원 상한 (CH-03 · I-17).
   *
   * <p><b>모집글의 정원을 쓰지 않는다.</b> 2차-MVP-기능-명세서.md 가 <i>"모집글의 정원은 상한으로 쓰지 않는다. 방 인원 상한은 100명 고정이다"</i>
   * 고 못박았고 PO-05 에도 같은 줄이 있다 — 정원은 표시용이고 비어 있을 수 있다 (I-03 이 NULL 을 허용한다).
   *
   * <p><b>방장을 포함해 센다.</b> I-17 이 「방 멤버는 100명을 넘지 않는다」 이고 방장도 멤버다 (CH-01). 그래서 초대로 늘어나는 자리는 99 다.
   */
  public static final int MEMBER_LIMIT = 100;

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

  /**
   * 초대받은 사람을 곧바로 멤버로 들인다 (CH-02).
   *
   * <p><b>수락 단계가 없다.</b> 명세가 <i>"누르는 즉시 멤버가 된다. 원치 않으면 나간다(CH-04)"</i> 고 정해서, 초대라는 이름의 중간 상태를 저장하지
   * 않는다 — 저장하면 CH-04 가 지우는 것과 이것이 지우는 것이 갈리고, 알림도 없어 (NT-06) 아무도 그 상태를 보지 못한다.
   *
   * <p><b>방장인지, 댓글을 썼는지는 여기서 보지 않는다.</b> 둘 다 모집글과 댓글이 아는 사실이라 이 애그리게이트 밖이다. {@code
   * ChatRoomInviteService} 가 읽어 판정한다.
   *
   * <p><b>나간 사람을 다시 들이지 않는다</b> (CH-02a · I-19). 수락 단계가 없어 초대가 곧 입장이므로, 이것이 없으면 나가도 계속 끌려 들어온다.
   * I-19 의 이중 방어가 「퇴장 이력 조회」 이고 그 이력이 {@code leftAt} 이 찬 행이다 — 그래서 퇴장이 행을 지우지 않는다.
   *
   * <p><b>상한을 여기서 센다</b> (CH-03 · I-17). 도메인-모델링.md 「5. 불변식」이 I-17 에 DB 이중 방어를 두지 않은 이유를 <i>"승인 주체가
   * 방장 하나라 경쟁이 없다"</i> 고 적었다 — 한 방을 채우는 요청은 모두 같은 사람에게서 오므로 두 요청이 101번째 자리를 동시에 집는 상황이 없다. 그래서 세는
   * 것으로 충분하고, 세는 자리는 멤버 목록을 쥔 여기다.
   */
  public ChatRoomMember invite(Long userId) {
    memberOf(userId)
        .ifPresent(
            member -> {
              throw new BusinessException(
                  member.isJoined()
                      ? ChatErrorCode.CHAT_ALREADY_MEMBER
                      : ChatErrorCode.CHAT_MEMBER_LEFT);
            });

    if (currentMembers().size() >= MEMBER_LIMIT) {
      throw new BusinessException(ChatErrorCode.CHAT_ROOM_MEMBER_LIMIT_EXCEEDED);
    }

    ChatRoomMember invited = ChatRoomMember.joining(this, userId);
    members.add(invited);

    return invited;
  }

  /**
   * 그 사람의 행 (나간 사람 포함).
   *
   * <p>나간 사람까지 보는 것이 {@code currentMembers} 와 다른 점이고 CH-02a 가 성립하는 이유다. 행이 방마다 하나뿐인 것은 {@code
   * uq_chat_room_member} 가 지킨다.
   */
  private Optional<ChatRoomMember> memberOf(Long userId) {
    return members.stream().filter(member -> member.getUserId().equals(userId)).findFirst();
  }

  /** 나가지 않은 멤버 (CH-18). 나간 사람의 행은 남지만 멤버는 아니다. */
  public List<ChatRoomMember> currentMembers() {
    return members.stream().filter(ChatRoomMember::isJoined).toList();
  }

  public List<ChatRoomMember> getMembers() {
    return Collections.unmodifiableList(members);
  }
}
