package com.duckmoim.chat.infra;

import com.duckmoim.chat.domain.ChatRoom;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 채팅방 저장소.
 *
 * <p><b>{@code findByPostId} 하나만 둔다.</b> CH-01 이 「방은 모집글 하나에 하나」로 정해 모집글로 찾는 것이 방을 찾는 기본 경로다 — 방
 * 상세(CH-06)가 방 번호로 찾는 것은 {@code findById} 이고, 내가 속한 방 목록(CH-05)은 멤버 조건과 마지막 메시지가 붙어 전용 쿼리가 생긴다. 그
 * 모양은 정렬 키와 응답 필드를 쥔 그 티켓이 정한다. 여기서 미리 지어내면 쓰지 않는 메서드가 남고, 그 메서드가 다음 담당의 기준선이 된다.
 *
 * <p><b>반환이 {@code Optional} 이지만 비어 있는 경우를 조회 경로가 다루지 않을 것이다.</b> CH-01a 가 배포 시점의 모집글에도 마이그레이션으로 방을
 * 만들어 「방이 없는 모집글」을 없앴다. 그래도 {@code Optional} 인 것은 없는 모집글 번호로 물을 수 있기 때문이다.
 */
public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

  /**
   * 모집글의 방을 애그리게이트째로 읽는다.
   *
   * <p><b>멤버를 함께 가져온다.</b> 멤버는 방 경계 안이라 (도메인-모델링.md 「3.1 경계와 트랜잭션 범위」) 방을 읽었는데 멤버를 못 읽는 상태가 애그리게이트
   * 로서 성립하지 않는다 — 지연 로딩으로 두면 트랜잭션 밖에서 멤버를 만지는 순간 터지고, <b>그 실패가 호출한 쪽의 트랜잭션 경계에 따라 갈린다.</b>
   *
   * <p>목록 조회(CH-05)가 이 메서드를 쓰지 않는 이유도 여기 있다. 거기서 필요한 것은 멤버 수와 마지막 메시지라 방마다 멤버를 다 끌어오면 방 수만큼 낭비가 된다
   * — 그 티켓이 전용 쿼리를 만든다.
   */
  @EntityGraph(attributePaths = "members")
  Optional<ChatRoom> findByPostId(Long postId);
}
