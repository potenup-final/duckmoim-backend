package com.duckmoim.chat.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 신고 처리 결과로 메시지를 가린다 (AD-09).
 *
 * <p><b>가리고, 커밋된 뒤에 방에 알린다</b> (STAR-147). 알리는 줄이 없던 때는 신고된 메시지가 방을 열어 둔 사람의 화면에 새로고침 전까지 남았다.
 *
 * <pre>
 * ① AdminMessageBlindWriter.blind    상태 · 감사 로그 · 커밋   (트랜잭션)
 * ② ChatMessageChangeFanout.publish  방에 알림              (트랜잭션 밖. 실패해도 블라인드는 성공)
 * </pre>
 *
 * <p><b>이 클래스에 트랜잭션을 걸지 않는다</b> — {@link ChatMessageDeleteService} 와 같은 이유다. ① 이 던지면 ② 에 닿지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AdminMessageBlindService {

  private final AdminMessageBlindWriter adminMessageBlindWriter;
  private final ChatMessageChangeFanout chatMessageChangeFanout;

  /** 메시지 하나를 가린다. 판정 규칙은 {@link AdminMessageBlindWriter#blind} 에 있다. */
  public void blind(Long messageId, Long adminUserId) {
    adminMessageBlindWriter.blind(messageId, adminUserId);

    chatMessageChangeFanout.publish(messageId);
  }
}
