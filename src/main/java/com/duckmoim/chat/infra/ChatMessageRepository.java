package com.duckmoim.chat.infra;

import com.duckmoim.chat.domain.Message;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 메시지 저장소 (CH-07).
 *
 * <p><b>목록 조회는 {@link ChatMessageQueryRepository} 가 진다</b> (CH-09 · STAR-112). 커서 조건이 있을 때만 붙어서 메서드
 * 이름으로 만들어지지 않는다 — {@code ChatRoomRepository} 가 {@code ChatRoomQueryRepository} 를 상속한 것과 같은 배치다.
 */
public interface ChatMessageRepository
    extends JpaRepository<Message, Long>, ChatMessageQueryRepository {

  /**
   * 같은 사람이 같은 식별자로 이미 보낸 것이 있는지 (I-20).
   *
   * <p><b>조건이 둘인 이유가 {@code uq_chat_message_sender_client_id} 와 같다.</b> 식별자만으로 찾으면 남이 보낸 메시지를 집을 수
   * 있고, 그 행이 「기존 건 반환」으로 응답에 실리면 본문이 그대로 새어 나간다. 식별자를 만드는 쪽이 클라이언트라 값을 신뢰할 근거가 없다.
   */
  Optional<Message> findBySenderIdAndClientMessageId(Long senderId, String clientMessageId);

  /**
   * 한 방의 메시지를 전부 지운다 (CH-19).
   *
   * <p><b>상태를 가리지 않는다.</b> 지운 메시지(CH-12)도 블라인드된 메시지(AD-09)도 본문을 들고 있는 행이고, 파기는 그 본문을 없애는 일이다 — 소프트
   * 삭제가 감추는 것은 화면이지 데이터가 아니다.
   *
   * <p><b>방 하나가 한 문장이다.</b> 방 안에서 다시 쪼개지 않는 것은 한 방의 메시지 수가 구조적으로 묶여 있기 때문이다 — 멤버가 100명 상한이고(CH-03)
   * 쓸 수 있는 구간이 만남시각 + 7일이다(CH-08). 청크의 단위는 방이다.
   *
   * <p><b>벌크 삭제라 영속성 컨텍스트를 비운다.</b> 같은 트랜잭션에서 읽어 둔 메시지가 있으면 지워진 행을 계속 들고 있게 된다.
   *
   * @return 지운 행 수
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM Message m WHERE m.roomId = :roomId")
  int deleteByRoomId(@Param("roomId") Long roomId);
}
