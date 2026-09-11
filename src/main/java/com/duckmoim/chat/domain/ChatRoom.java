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
   * <p><b>상한을 여기서 센다</b> (CH-03 · I-17). 도메인-모델링.md 「5. 불변식」이 I-17 에 DB 이중 방어를 두지 않았고, 세는 자리는 멤버
   * 목록을 쥔 여기다.
   *
   * <p><b>세는 것은 동시 요청에 안전하지 않다.</b> 문서가 이중 방어를 뺀 이유로 적은 <i>"승인 주체가 방장 하나라 경쟁이 없다"</i> 는 그대로는 성립하지
   * 않는다 — <b>주체가 하나인 것과 요청이 하나인 것은 다르다.</b> 같은 방장이 두 번 누르면 트랜잭션도 둘이고, 둘 다 커밋 전이면 둘 다 99를 세고 지나
   * 101명이 된다. 중복 초대가 같은 창에서 겹치면 {@code uq_chat_room_member} 가 두 번째를 거부한다.
   *
   * <p><b>그 위반은 이제 500 이 아니라 409 로 나간다</b> (PR #103 리뷰). {@code ChatRoomInviteService#invite} 가
   * {@code flush()} 를 명시적으로 불러 커밋 시점의 실패를 메서드 안으로 끌어와 {@code CHAT_ALREADY_MEMBER} 로 옮긴다 — 사용자가 받는
   * 결과는 순차로 왔을 때와 같다.
   *
   * <p><b>그럼에도 세는 것으로 둔다.</b> 겹치는 창이 조회 셋과 insert 하나 사이의 수 ms 라 사람의 더블클릭은 대개 첫 요청이 커밋된 뒤에 도착해 409
   * ({@code CHAT_ALREADY_MEMBER}) 로 걸린다. 상한 쪽은 99명짜리 방이 먼저 있어야 하고 최악이 101명이다. 둘 다 지금 규모에서 값이 작아,
   * 초대를 직렬화하고 잠금 대기라는 새 실패 모드를 들이는 것보다 감수하는 편이 싸다.
   *
   * <p><b>이 판단을 다른 명령으로 옮기지 마라.</b> 근거는 「경쟁이 없다」가 아니라 <b>「경쟁이 나도 드물고 손해가 작다」</b> 이고, 그 셈은 이 명령의
   * 것이다. 메시지 전송(CH-07)은 동시에 누르는 사람이 여럿이라 같은 셈이 나오지 않는다.
   *
   * <p><b>바꿀 때는 증거가 먼저다.</b> 남아 있는 것은 101명 상한 레이스뿐이다 — 방이 실제로 그렇게 커지는 것이 관측되면 그때 다시 정한다. 제약 위반 500
   * 은 더 이상 그 증거가 아니다. 위 문단대로 이제 409 로 번역돼 로그에도 5xx 로 잡히지 않는다.
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
