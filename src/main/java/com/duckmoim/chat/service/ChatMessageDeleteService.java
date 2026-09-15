package com.duckmoim.chat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 메시지 삭제 (CH-12).
 *
 * <p><b>지우고, 커밋된 뒤에 방에 알린다</b> (STAR-147 · QA-EYE-04). 알리는 줄이 없던 때는 방을 열어 둔 상대에게 지운 말이 새로고침 전까지
 * 남았다.
 *
 * <pre>
 * ① ChatMessageDeleteWriter.delete   판정 · 저장 · 커밋   (트랜잭션)
 * ② ChatMessageChangeFanout.publish  방에 알림          (트랜잭션 밖. 실패해도 삭제는 성공)
 * </pre>
 *
 * <p><b>이 클래스에 트랜잭션을 걸지 않는다.</b> 걸면 ② 가 커밋 전에 돌아, 롤백된 삭제가 화면에만 반영될 수 있다 — {@code
 * ChatMessageSendService} 가 같은 이유로 트랜잭션 밖에 있다.
 *
 * <p>① 이 던지면 ② 에 닿지 않는다. 거절된 삭제는 알릴 것이 없다.
 */
@Service
@RequiredArgsConstructor
public class ChatMessageDeleteService {

  private final ChatMessageDeleteWriter chatMessageDeleteWriter;
  private final ChatMessageChangeFanout chatMessageChangeFanout;

  /** 지운다 (CH-12). 판정 규칙은 {@link ChatMessageDeleteWriter#delete} 에 있다. */
  public void delete(Long roomId, Long messageId, Long requesterId) {
    chatMessageDeleteWriter.delete(roomId, messageId, requesterId);

    chatMessageChangeFanout.publish(messageId);
  }
}
