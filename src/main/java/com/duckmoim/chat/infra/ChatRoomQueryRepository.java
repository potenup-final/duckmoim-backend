package com.duckmoim.chat.infra;

import java.util.List;

/**
 * 방 목록 조회 (CH-05).
 *
 * <p>{@link ChatRoomRepository} 의 물려받은 조회 메서드들과 다른 자리다 — 그 둘은 방 하나를 애그리게이트째로 읽고, 이것은 여러 방을 훑으며 방마다
 * 멤버 수만 세고 모집글 제목·만남시각을 함께 읽는다. 애그리게이트를 방 수만큼 통째로 끌어오면 낭비라, {@code ChatRoomRepository} javadoc 이
 * 예고한 대로 전용 쿼리를 낸다.
 *
 * <p>커스텀 프래그먼트로 빼고 {@link ChatRoomRepository} 가 함께 상속한다 — service 에는 저장소 하나만 주입된다 ({@code
 * CompanionPostQueryRepository} 와 같은 방식).
 */
public interface ChatRoomQueryRepository {

  /**
   * 요청자가 지금 멤버인 방만 골라 {@code (meetAt, roomId)} 오름차순으로 읽는다 (CH-05).
   *
   * <p><b>나간 방은 나오지 않는다.</b> 「지금 멤버」 조건이 {@code leftAt IS NULL} 이라 — 검증 기준 「나간 방은 목록에 없다」가 그대로 이
   * 조건이다.
   *
   * <p><b>커서가 없다.</b> 한 사람이 속한 방 수가 작아 페이지네이션의 이득보다 화면 쪽 구현 비용이 크다고 판단했다 (이 티켓 계획의 참고 사항).
   */
  List<ChatRoomSummary> findSummariesForMember(Long userId);
}
