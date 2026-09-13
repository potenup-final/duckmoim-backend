package com.duckmoim.chat.infra;

import java.time.LocalDateTime;
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

  /**
   * 방 상세(CH-06)의 멤버 목록. 지금 멤버만, 들어온 순서로 읽는다.
   *
   * <p><b>탈퇴한 멤버는 빠지지 않는다.</b> 익명화는 응답 조립 시점에 {@link AuthoredChatRoomMember#display} 가 한다 — 나간 사람만
   * 여기서 거른다({@code leftAt IS NULL}).
   */
  List<AuthoredChatRoomMember> findMembersOf(Long roomId);

  /**
   * 보관 기간이 지난 방의 번호를 오래된 것부터 읽는다 (CH-19).
   *
   * <p><b>모집글을 조인해야 답이 나온다.</b> 기준이 「모집글 마감 후 90일」이라 방 자신은 그 시각을 모른다 — 애그리게이트 밖은 ID 로만 참조하므로
   * (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」) 여기서 JPQL 로 잇는다. 목록 조회가 같은 이유로 같은 자리에 있다.
   *
   * <p><b>이미 파기한 방은 빼고 준다.</b> 파기된 방은 메시지도 사진도 0건이라 다시 지워도 결과가 같지만, 빼지 않으면 대상 집합이 시간이 갈수록 자라고 「집었는데
   * 지운 것이 0건」인 주기가 정상이 되어 고장과 구별되지 않는다.
   *
   * <p><b>방 번호만 준다.</b> 뒤따르는 일이 이미지 못박기 · S3 삭제 · 메시지 삭제라 엔티티가 필요한 자리는 마지막 하나뿐이고, 그것도 다른 트랜잭션이다 —
   * 여기서 애그리게이트를 통째로 끌어오면 멤버까지 따라온다.
   *
   * <p><b>오래된 것부터 준다.</b> 한 주기가 상한만큼만 집으므로 순서가 없으면 같은 묶음을 반복해 집고 옛 방이 남는다. 두 인스턴스가 같은 순서로 훑는다는 뜻이기도
   * 하다 (ADR 0009 — 잠그지 않는 대신 순서를 맞춘다).
   *
   * @param cutoffInUtc 이 시각보다 <b>앞서</b> 마감된 모집글이 대상이다. 경계에 정확히 걸친 것은 남는다
   * @param limit 한 청크가 집을 최대 방 수
   */
  List<Long> findPurgeableRoomIds(LocalDateTime cutoffInUtc, int limit);
}
