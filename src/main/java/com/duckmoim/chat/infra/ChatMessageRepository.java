package com.duckmoim.chat.infra;

import com.duckmoim.chat.domain.Message;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
