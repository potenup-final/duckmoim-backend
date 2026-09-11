package com.duckmoim.chat.presentation;

import com.duckmoim.chat.service.SentMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 보낸 메시지 (CH-07).
 *
 * <p><b>재시도로 돌아온 기존 건과 구별되지 않는다.</b> I-20 의 「기존 건 반환」이 그래야 뜻을 갖는다 — 클라이언트가 두 경우를 나눠 처리할 이유가 없고, 나뉘는
 * 순간 재시도가 안전하다는 성질이 사라진다.
 *
 * <p><b>{@code clientMessageId} 를 돌려주지 않는다.</b> 보낸 쪽이 방금 만든 값이라 알려줄 것이 없다.
 */
public record ChatMessageResponse(
    @Schema(description = "메시지 번호", example = "51") Long messageId,
    @Schema(description = "방 번호", example = "3") Long roomId,
    @Schema(description = "보낸 사람의 회원번호", example = "7") Long senderId,
    @Schema(description = "본문", example = "8시에 3번 출구에서 봬요") String content,
    @Schema(description = "보낸 시각 (KST)", example = "2026-09-11T20:10:00+09:00")
        OffsetDateTime createdAt) {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  public static ChatMessageResponse from(SentMessage message) {
    return new ChatMessageResponse(
        message.messageId(),
        message.roomId(),
        message.senderId(),
        message.content(),
        toKst(message.createdAt()));
  }

  /**
   * 저장은 UTC, 응답은 KST 다.
   *
   * <p>{@code BaseEntity} 가 {@code createdAt} 을 {@code LocalDateTime.now(ZoneOffset.UTC)} 로 채우므로
   * 그대로 내보내면 화면에 아홉 시간 전으로 찍힌다. {@code AuditLogItemResponse#toKst} 가 같은 자리에서 같은 변환을 쓴다.
   */
  private static OffsetDateTime toKst(LocalDateTime storedInUtc) {
    return storedInUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toOffsetDateTime();
  }
}
