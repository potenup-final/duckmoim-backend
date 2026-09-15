package com.duckmoim.chat.service;

import com.duckmoim.chat.infra.ChatFanout;
import com.duckmoim.chat.infra.ChatFanoutCodec;
import com.duckmoim.chat.infra.ChatMessageRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 이미 나간 메시지의 상태가 바뀌었다고 방에 알린다 (CH-12 · AD-09 → CH-10).
 *
 * <p><b>이것이 없어서 지운 말이 상대 화면에 남았다</b> (STAR-147 · QA-EYE-04). 삭제와 블라인드가 DB 에만 적고 팬아웃을 부르지 않아, 방을 열어
 * 둔 사람은 새로고침하기 전까지 옛 말풍선을 봤다.
 *
 * <pre>
 * 전송   저장 ─▶ 팬아웃 ─▶ 모든 인스턴스의 연결        (ChatMessageSendService)
 * 삭제   저장 ─▶ 팬아웃 ─▶ 모든 인스턴스의 연결        (이 클래스)
 * 블라인드 저장 ─▶ 팬아웃 ─▶ 모든 인스턴스의 연결      (이 클래스)
 * </pre>
 *
 * <p><b>트랜잭션 밖에서, 커밋이 끝난 뒤에 부른다.</b> 커밋 전에 보내면 롤백된 삭제가 화면에만 반영되고, 트랜잭션 안에서 보내면 Redis 왕복 동안 DB 커넥션을
 * 쥔다 — {@code ChatMessageSendService} / {@code ChatMessageWriter} 가 빈을 나눈 것과 같은 근거다.
 *
 * <p><b>다시 읽어서 싣는다.</b> 부르는 쪽이 방금 바꾼 엔티티를 넘기지 않는 것은, 보낸 사람 표시(탈퇴 익명화)와 본문 끊기를 {@code
 * AuthoredMessage#toEvent} 한 자리에서 만들기 위해서다. 전송 · 재전송이 같은 자리를 쓴다.
 *
 * <p><b>던지지 않는다.</b> 상태 변경은 이미 커밋됐다. 여기서 터진 예외가 올라가면 사용자는 실패를 보는데, 다시 눌러도 「이미 지운 메시지」 404 가 돌아온다 —
 * {@code ChatMessageFanoutFailureTest} 가 전송에서 막아 둔 것과 같은 모양의 구멍이다. 실시간 전달만 건너뛰고, 화면은 다음 조회에서 맞춰진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageChangeFanout {

  private final ChatMessageRepository chatMessageRepository;
  private final ChatFanoutCodec chatFanoutCodec;
  private final ChatFanout chatFanout;
  private final Clock clock;

  /** 그 메시지의 지금 상태를 그 방에 알린다. 조회·직렬화·발행 어느 것이 실패해도 조용히 끝난다. */
  public void publish(Long messageId) {
    try {
      chatMessageRepository
          .findAuthoredById(messageId)
          .map(message -> message.toEvent(clock))
          .ifPresent(
              event -> {
                String payload = chatFanoutCodec.encodeMessageChanged(event);
                if (payload != null) {
                  chatFanout.publish(event.roomId(), payload);
                }
              });
    } catch (RuntimeException e) {
      log.warn(
          "[ChatMessageChangeFanout.publish] 상태 변경 조회 실패 — 실시간 전달만 건너뛴다. messageId={} cause={}",
          messageId,
          e.getClass().getSimpleName());
    }
  }
}
