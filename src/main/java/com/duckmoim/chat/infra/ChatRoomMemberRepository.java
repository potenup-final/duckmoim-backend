package com.duckmoim.chat.infra;

import com.duckmoim.chat.domain.ChatRoomMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 멤버 행만 건드리는 갱신 (CH-13).
 *
 * <p><b>멤버는 {@code ChatRoom} 애그리게이트 안이라 보통은 방을 통해 다룬다.</b> 그런데 읽은 지점은 <b>방 행을 건드리지 않는 것</b>이 요구사항이라
 * (도메인 3.1 · CH-13 의 검증 기준 둘째 줄) 그 한 가지 갱신만 여기로 낸다.
 *
 * <p><b>저장소를 따로 둔 것이 애그리게이트를 깨는 것은 아니다.</b> 방을 거치지 않고 <b>멤버를 만들거나 지우는</b> 길은 여기 없다 — 있는 것은 자기 행의 읽은
 * 지점을 앞으로 미는 문장 하나뿐이다.
 */
public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long> {

  /**
   * 읽은 지점을 앞으로만 민다 (CH-13).
   *
   * <p><b>엔티티 저장이 아니라 한 문장인 이유</b> — 엔티티로 바꾸려면 방을 통해 멤버를 찾아야 하고, 그러면 방 행을 읽는다. 이 문장은 {@code
   * chat_room_member} 한 행만 잠근다.
   *
   * <p><b>뒤로 가지 않는다.</b> {@code lastReadMessageId <} 조건이 그것이다. 위로 스크롤해 옛 메시지를 보다가 그 지점을 보내도 배지가
   * 되살아나지 않고, 같은 요청을 두 번 보내도 결과가 같다 — 알림의 읽음 처리(NT-09)가 <i>"배지를 지우는 동작이라 몇 번을 불러도 같은 결과여야 한다"</i> 로
   * 정한 성질과 같다.
   *
   * <p><b>나간 사람은 제외한다.</b> 퇴장 행은 남지만 멤버가 아니다 (CH-18).
   *
   * <p><b>그 방에 실제로 있는 번호까지만 민다.</b> 마지막 {@code MAX} 조건이 그것이다 — 앞으로만 미는 규칙과 겹치면 <b>범위 밖 값 한 번이 영구가
   * 되기 때문</b>이다. 클라이언트가 {@code messageId} 대신 {@code Date.now()} 를 보내면 읽은 지점이 수천억으로 올라가고, 그 뒤로는 무엇이
   * 와도 그보다 작아 배지가 영영 0 이 된다. 되돌리려면 뒤로 가야 하는데 그것을 위 조건이 막는다. 악의가 아니라 <b>실수로 걸리는 쪽</b>이다 — 타임스탬프 · 다른
   * 방의 번호가 모두 같은 결과를 낸다 (PR 리뷰).
   *
   * <p><b>깎지 않고 거부한다.</b> 방의 최대 번호로 깎는 방법도 있지만 그것은 「방 끝까지 읽었다」를 <b>대신 단언하는 것</b>이라, 정말 안 읽은 말이 읽은
   * 것이 된다. 거부하면 배지가 실제보다 높게 남는데 — <b>그쪽이 안전한 실패다.</b> 배지가 남는 것은 사용자가 보고 알 수 있고, 0 이 되는 것은 온 메시지를
   * 숨긴다. 알림 억제가 <i>"억제는 부속이고 알림은 본 기능이라 열리는 쪽으로 실패한다"</i> 로 정한 것과 같은 방향이다.
   *
   * <p><b>거부가 예외는 아니다.</b> 위의 「뒤로 가지 않는다」와 같은 모양으로 0행이다 — 읽음 표시는 배지를 지우는 곁가지라 여기서 던지면 대화가 그것 때문에
   * 끊긴다.
   *
   * @return 민 행 수. 0 은 「이미 그보다 앞서 있다」·「그 방에 없는 번호다」·「멤버가 아니다」 셋 중 하나이고, 마지막을 가르는 것은 부르는 쪽의 멤버 판정이다
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      UPDATE ChatRoomMember m
         SET m.lastReadMessageId = :lastReadMessageId
       WHERE m.room.id = :roomId
         AND m.userId = :userId
         AND m.leftAt IS NULL
         AND (m.lastReadMessageId IS NULL OR m.lastReadMessageId < :lastReadMessageId)
         AND :lastReadMessageId <= (SELECT MAX(msg.id)
                                      FROM Message msg
                                     WHERE msg.roomId = :roomId)
      """)
  int advanceLastRead(
      @Param("roomId") Long roomId,
      @Param("userId") Long userId,
      @Param("lastReadMessageId") Long lastReadMessageId);
}
