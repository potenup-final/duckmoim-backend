package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.MessageEvent;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.ChatFanout;
import com.duckmoim.chat.infra.ChatMessageRepository;
import com.duckmoim.chat.infra.MessageEventCodec;
import com.duckmoim.common.exception.BusinessException;
import java.time.Clock;
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
  private final ChatFanout chatFanout;
  private final MessageEventCodec messageEventCodec;
  private final Clock clock;

  /**
   * 보낸다. 이미 같은 식별자로 보낸 것이 있으면 그것을 그대로 돌려준다 (CH-07 · I-20).
   *
   * <p><b>먼저 읽는 것은 빠른 길일 뿐 보증이 아니다.</b> 조회와 저장 사이에 같은 요청이 하나 더 들어오면 둘 다 조회를 지나고, 그때 두 번째를 막는 것은 유니크
   * 제약이다. 그 위반을 잡아 다시 읽는 것이 아래 {@code catch} 다.
   *
   * <p><b>방 존재·멤버·구간 판정을 여기서 하지 않는다.</b> 전부 {@link ChatMessageWriter} 안이다. 다만 <b>빠른 길로 빠지면 그 판정을
   * 지나지 않는다</b> — 이미 저장된 메시지가 있다는 것은 그때 판정을 통과했다는 뜻이고, 같은 요청에 대한 답이 시간에 따라 갈리면 재시도가 안전하지 않게 된다. 방에서
   * 나간 뒤 재시도한 클라이언트가 403 대신 자기 메시지를 돌려받는데, <b>이미 보낸 자기 메시지라 새로 새는 것이 없다.</b>
   *
   * <p><b>다만 찾아온 행이 이 요청의 재시도가 맞는지는 대조한다</b> — {@link #requireSameRequest}. 방이나 본문이 다르면 재시도가 아니라
   * 식별자 재사용이고, 그대로 돌려주면 이번에 보낸 말이 200 과 함께 사라진다.
   */
  public SentMessage send(Long roomId, Long senderId, String clientMessageId, String content) {
    Optional<SentMessage> retried = alreadySent(roomId, senderId, clientMessageId, content);
    if (retried.isPresent()) {
      // 재시도는 발행하지 않는다. 먼저 온 요청이 이미 발행했고, 다시 보내면 같은 말풍선이
      // 두 번 뜬다 — 클라이언트가 messageId 로 거르더라도 통로를 낭비할 이유가 없다.
      return retried.get();
    }

    SentMessage sent = writeOrTakeExisting(roomId, senderId, clientMessageId, content);
    fanOut(sent.messageId());

    return sent;
  }

  /**
   * 저장된 메시지를 같은 방의 다른 인스턴스에 알린다 (CH-10).
   *
   * <p><b>커밋된 뒤에 부른다.</b> 이 클래스에 {@code @Transactional} 이 없고 {@link ChatMessageWriter} 가 반환된 시점이 곧
   * 커밋된 시점이다 — 트랜잭션 안에서 발행하면 받는 쪽이 아직 보이지 않는 메시지를 받고, 롤백되면 <b>없는 메시지를 받은 셈</b>이 된다. STAR-111 이 중복
   * 처리를 위해 빈을 둘로 나눠 둔 것이 여기서 한 번 더 값을 한다.
   *
   * <p><b>보낸 사람 정보를 다시 읽는다.</b> {@link SentMessage} 에는 닉네임·아바타가 없는데 받는 쪽 말풍선에는 필요하다. 목록 조회와 같은 조인을
   * 쓰므로 실시간으로 뜬 것과 새로고침해서 뜬 것이 같은 값이다.
   *
   * <p><b>사건으로 바꾸는 일은 {@code AuthoredMessage#toEvent} 가 한다</b> (CH-11). 재연결 재전송도 같은 메서드를 쓴다 — 두 경로가
   * 각자 조립하면 실시간으로 뜬 말풍선과 재연결해서 온 말풍선이 갈린다.
   *
   * <p><b>실패해도 던지지 않는다.</b> 조회가 비거나 직렬화가 실패하면 발행을 건너뛴다 — {@code ChatFanout} 의 계약이 「팬아웃 장애가 전송을 깨지
   * 않는다」이고, 사용자는 새로고침하면 자기 말을 본다. 정본은 MySQL 이다.
   */
  private void fanOut(Long messageId) {
    chatMessageRepository
        .findAuthoredById(messageId)
        .map(message -> message.toEvent(clock))
        .ifPresent(this::publish);
  }

  private void publish(MessageEvent event) {
    String payload = messageEventCodec.encode(event);
    if (payload == null) {
      return;
    }

    chatFanout.publish(event.roomId(), payload);
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
      return alreadySent(roomId, senderId, clientMessageId, content).orElseThrow(() -> e);
    }
  }

  private Optional<SentMessage> alreadySent(
      Long roomId, Long senderId, String clientMessageId, String content) {

    return chatMessageRepository
        .findBySenderIdAndClientMessageId(senderId, clientMessageId)
        .map(SentMessage::from)
        .map(sent -> requireSameRequest(sent, roomId, content));
  }

  /**
   * 찾아온 행이 <b>이 요청의 재시도가 맞는지</b> 대조한다 (PR #125 리뷰).
   *
   * <p><b>이 대조가 없으면 사용자의 말이 200 과 함께 사라진다.</b>
   *
   * <pre>
   * A방에 "8시에 봬요"  (식별자 abc)  →  저장
   * B방에 "저 못 가요"  (abc 재사용)  →  200 { roomId: A, content: "8시에 봬요" }
   *                                     B방에는 아무것도 안 남고 아무 신호도 없다
   * </pre>
   *
   * <p><b>조회 조건에 {@code roomId} 를 더하는 방식으로는 못 고친다.</b> 유니크 제약이 {@code (sender_id,
   * client_message_id)} 라 B방 INSERT 가 제약에 걸리고, 방까지 따지는 재조회는 그 행을 못 찾아 {@code orElseThrow} 의 500 이
   * 된다. 유니크에 {@code room_id} 를 더하는 것은 반대로 「한 건만 저장된다」(I-20)를 깬다 — <b>찾은 뒤에 대조하는 것</b>만 남는다.
   *
   * <p>멱등 키의 일반 규칙과 같다 — <b>같은 키 + 같은 파라미터면 원래 응답, 같은 키 + 다른 파라미터면 에러.</b> 파라미터가 둘이라 방과 본문을 함께 본다.
   *
   * <p><b>사람이 만들 수 있는 상황이 아니다.</b> 앱이 전송마다 새 식별자를 만들면 절대 나지 않는다. 그래도 409 로 돌려주는 이유는, 앱이 그 약속을 어겼을 때
   * <b>화면에 「전송 실패」가 뜨는 것</b>이 말이 조용히 사라지는 것보다 낫기 때문이다.
   */
  private SentMessage requireSameRequest(SentMessage sent, Long roomId, String content) {
    if (!sent.roomId().equals(roomId) || !sent.content().equals(content)) {
      throw new BusinessException(ChatErrorCode.CHAT_CLIENT_MESSAGE_ID_REUSED);
    }

    return sent;
  }
}
