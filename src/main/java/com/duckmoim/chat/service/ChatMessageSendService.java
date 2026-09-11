package com.duckmoim.chat.service;

import com.duckmoim.chat.infra.ChatMessageRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 메시지를 보낸다 (CH-07). <b>같은 것을 두 번 보내도 한 건이다</b> (I-20).
 *
 * <p><b>이 클래스에 {@code @Transactional} 이 없는 것이 설계다.</b> 저장과 판정은 {@link ChatMessageWriter} 가 자기
 * 트랜잭션에서 하고, 여기는 그 밖에서 중복을 가려낸다.
 *
 * <p>왜 밖이어야 하는가 —
 *
 * <pre>
 * 같은 트랜잭션 안에서 잡으려 하면
 *   uq_chat_message_sender_client_id 위반이 flush 에서 터진다
 *      → 영속성 컨텍스트가 롤백 표시로 죽는다
 *      → 「기존 건」을 다시 읽는 조회 자체가 성립하지 않는다
 * </pre>
 *
 * <p>{@code NotificationDispatchBatch} / {@code NotificationDispatchService} 가 같은 모양이다 — 실패 뒤에 할 일이
 * 있으면 그 일은 실패한 트랜잭션 밖에 있어야 한다.
 *
 * <p><b>{@code ChatRoomInviteService} 와는 답이 다르다.</b> 그쪽도 유니크 위반을 잡지만 결과가 409 라 같은 트랜잭션에서 던지고 끝난다.
 * 여기는 <b>돌려줄 값이 있다</b> — 초대의 「순차로 왔을 때와 같은 답」은 409 이고, 전송의 그것은 <b>먼저 저장된 메시지</b>다.
 *
 * <p><b>재시도가 안전하다는 것이 이 기능의 계약이다.</b> 전송 응답을 못 받은 클라이언트가 다시 보내는 것이 정상 경로이므로, 두 번째 응답이 첫 번째와 구별되면 안
 * 된다. 그래서 기존 건도 새로 보낸 것과 같은 {@link SentMessage} 로 나간다.
 */
@Service
@RequiredArgsConstructor
public class ChatMessageSendService {

  private final ChatMessageWriter chatMessageWriter;
  private final ChatMessageRepository chatMessageRepository;

  /**
   * 보낸다. 이미 같은 식별자로 보낸 것이 있으면 그것을 그대로 돌려준다 (CH-07 · I-20).
   *
   * <p><b>먼저 읽는 것은 빠른 길일 뿐 보증이 아니다.</b> 조회와 저장 사이에 같은 요청이 하나 더 들어오면 둘 다 조회를 지나고, 그때 두 번째를 막는 것은 유니크
   * 제약이다. 그 위반을 잡아 다시 읽는 것이 아래 {@code catch} 다.
   *
   * <p><b>방 존재·멤버·구간 판정을 여기서 하지 않는다.</b> 전부 {@link ChatMessageWriter} 안이다. 다만 <b>빠른 길로 빠지면 그 판정을
   * 지나지 않는다</b> — 이미 저장된 메시지가 있다는 것은 그때 판정을 통과했다는 뜻이고, 같은 요청에 대한 답이 시간에 따라 갈리면 재시도가 안전하지 않게 된다. 방에서
   * 나간 뒤 재시도한 클라이언트가 403 대신 자기 메시지를 돌려받는데, <b>이미 보낸 자기 메시지라 새로 새는 것이 없다.</b>
   */
  public SentMessage send(Long roomId, Long senderId, String clientMessageId, String content) {
    return alreadySent(senderId, clientMessageId)
        .orElseGet(() -> writeOrTakeExisting(roomId, senderId, clientMessageId, content));
  }

  private SentMessage writeOrTakeExisting(
      Long roomId, Long senderId, String clientMessageId, String content) {

    try {
      return chatMessageWriter.write(roomId, senderId, clientMessageId, content);
    } catch (DataIntegrityViolationException e) {
      // uq_chat_message_sender_client_id. 같은 클라이언트가 두 번 보내 둘 다 조회를 지난 경우다 —
      // 먼저 커밋된 쪽이 이미 저장했으므로 순차로 왔을 때와 같은 답을 준다.
      //
      // 못 찾으면 그 예외를 그대로 올린다. 이 표의 유니크 제약이 하나뿐이라 「위반했는데 그 행이
      // 없다」는 우리가 아는 원인이 없고, 새 에러 코드를 지어 덮으면 모르는 고장이 아는 고장으로
      // 둔갑한다. 500 으로 나가면서 로그에 스택이 남는 편이 낫다.
      return alreadySent(senderId, clientMessageId).orElseThrow(() -> e);
    }
  }

  private Optional<SentMessage> alreadySent(Long senderId, String clientMessageId) {
    return chatMessageRepository
        .findBySenderIdAndClientMessageId(senderId, clientMessageId)
        .map(SentMessage::from);
  }
}
