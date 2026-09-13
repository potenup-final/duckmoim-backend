package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.Message;
import java.time.LocalDateTime;

/**
 * 보낸 메시지 한 건 (CH-07).
 *
 * <p>엔티티를 service 밖으로 내보내지 않기 위한 결과 객체다 (아키텍처 컨벤션 「service · 금지」). {@code ChatRoomInvitation} 과 같은
 * 배치다.
 *
 * <p><b>{@code imageId} 를 담는다</b> (CH-14). 응답에 필요해서만이 아니라 <b>멱등 대조의 입력</b>이기도 하다 — 같은 식별자로 본문은 같고
 * 사진만 다르게 보내면, 대조하지 않으면 이번 사진이 200 과 함께 사라진다 ({@code ChatMessageSendService#requireSameRequest}).
 *
 * <p><b>재시도로 돌아온 기존 건도 같은 모양으로 나간다.</b> I-20 의 「기존 건 반환」이 성립하려면 처음 보낸 응답과 구별되지 않아야 한다 — 클라이언트가 두
 * 경우를 나눠 처리할 이유가 없고, 나뉘면 재시도가 안전하다는 성질이 사라진다.
 */
public record SentMessage(
    Long messageId,
    Long roomId,
    Long senderId,
    String content,
    Long imageId,
    LocalDateTime createdAt) {

  public static SentMessage from(Message message) {
    return new SentMessage(
        message.getId(),
        message.getRoomId(),
        message.getSenderId(),
        message.getContent(),
        message.getImageId(),
        message.getCreatedAt());
  }
}
