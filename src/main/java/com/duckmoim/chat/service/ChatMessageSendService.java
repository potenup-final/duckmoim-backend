package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.MessageEvent;
import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.chat.infra.AuthoredMessage;
import com.duckmoim.chat.infra.ChatFanout;
import com.duckmoim.chat.infra.ChatFanoutCodec;
import com.duckmoim.chat.infra.ChatMessageRepository;
import com.duckmoim.chat.infra.ChatPresence;
import com.duckmoim.common.exception.BusinessException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageSendService {

  private final ChatMessageWriter chatMessageWriter;
  private final ChatMessageRepository chatMessageRepository;
  private final ChatFanout chatFanout;
  private final ChatFanoutCodec chatFanoutCodec;
  private final ChatPresence chatPresence;

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
   * <p><b>실패해도 던지지 않는다.</b> 조회가 비거나 직렬화가 실패하면 발행을 건너뛴다 — {@code ChatFanout} 의 계약이 「팬아웃 장애가 전송을 깨지
   * 않는다」이고, 사용자는 새로고침하면 자기 말을 본다. 정본은 MySQL 이다.
   *
   * <p><b>그 계약을 조회 단계까지 넓힌다</b> (PR #138 리뷰). {@code Optional} 은 <b>값이 없는 경우</b>만 다루지 조회 자체가 터지는
   * 경우를 막지 않는다 — 커넥션 고갈이나 타임아웃은 {@code RuntimeException} 으로 올라온다. {@code ChatFanout#publish} 와
   * {@code ChatFanoutCodec} 은 이미 삼키는데 <b>그 둘 앞의 한 줄만 안 삼키고 있었다.</b>
   *
   * <p><b>새는 자리가 500 하나가 아니다.</b> 저장은 이미 커밋된 뒤라 그 예외는 <b>재시도로도 복구되지 않는다.</b>
   *
   * <pre>
   * 1차  저장 COMMIT ✅ → fanOut 에서 터짐 → 사용자에게 500
   * 2차  alreadySent 가 찾아내 즉시 200 으로 반환한다 (재시도는 발행하지 않는다)
   *         ▲ 발행 경로를 아예 지나지 않는다
   *           → 이 메시지는 영영 실시간으로 나가지 않는다. 새로고침해야 보인다
   * </pre>
   *
   * <p><b>{@code RuntimeException} 으로 넓게 잡는다.</b> 좁히려면 어떤 예외가 올라오는지를 이 클래스가 알아야 하는데, 그것은 JPA 구현과
   * 드라이버가 정하는 값이라 <b>여기서 아는 것이 오히려 결합이다.</b> 어차피 「무엇이 나든 전송은 안 깨진다」가 계약이다.
   */
  private void fanOut(Long messageId) {
    try {
      chatMessageRepository
          .findAuthoredById(messageId)
          .map(ChatMessageSendService::toEvent)
          .ifPresent(this::publish);
    } catch (RuntimeException e) {
      // 본문을 로그에 남기지 않는다. 어느 메시지였는지와 무엇이 터졌는지만 남긴다.
      log.warn(
          "[ChatMessageSendService.fanOut] 팬아웃 조회 실패 — 실시간 전달만 건너뛴다. messageId={} cause={}",
          messageId,
          e.getClass().getSimpleName());
    }
  }

  private void publish(MessageEvent event) {
    String payload = chatFanoutCodec.encodeMessage(event);
    if (payload == null) {
      return;
    }

    chatFanout.publish(event.roomId(), payload);
  }

  private static MessageEvent toEvent(AuthoredMessage message) {
    return new MessageEvent(
        message.messageId(),
        message.roomId(),
        message.senderId(),
        message.nickname(),
        message.profileImageUrl(),
        message.content(),
        message.status(),
        message.createdAt());
  }

  /**
   * <b>접속 집합을 트랜잭션 밖에서 읽는다</b> (NT-07).
   *
   * <p>{@link ChatMessageWriter#write} 안으로 옮기면 Redis 왕복이 DB 커넥션을 쥔 채로 일어난다. {@code
   * ChatRoomMembershipReader} 가 적어 둔 것과 같은 함정이다 —
   *
   * <pre>
   * Redis 지연
   *    전송 10건 × DB 커넥션 점유
   *       → HikariCP 풀 고갈
   *          → 로그인·모집글·댓글까지 커넥션 대기
   * </pre>
   *
   * <p><b>빈이 둘로 나뉜 것이 여기서 한 번 더 값을 한다.</b> 원래는 {@code I-20} 의 유니크 위반을 트랜잭션 밖에서 잡으려고 가른 경계인데, 그 밖이 곧
   * Redis 를 읽어도 되는 자리다.
   *
   * <p><b>읽은 값이 잠깐 낡는다.</b> 읽은 뒤 저장 전에 누가 스트림을 닫으면 그 사람은 알림을 못 받는다. 반대 방향(닫은 사람이 알림을 하나 더 받는 것)보다
   * 나쁜 쪽이지만, 창이 밀리초이고 그 사람은 <b>방금까지 그 화면을 보고 있었다.</b>
   */
  private SentMessage writeOrTakeExisting(
      Long roomId, Long senderId, String clientMessageId, String content) {

    try {
      return chatMessageWriter.write(
          roomId, senderId, clientMessageId, content, chatPresence.viewers(roomId));
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
