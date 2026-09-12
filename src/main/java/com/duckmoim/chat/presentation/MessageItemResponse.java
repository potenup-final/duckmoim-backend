package com.duckmoim.chat.presentation;

import com.duckmoim.chat.domain.MessageStatus;
import com.duckmoim.chat.service.MessageView;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 목록의 메시지 한 건 (CH-09 · CH-12).
 *
 * <p><b>지운 메시지는 {@code content} 키가 아예 없다.</b> {@code null} 이 아니다 — API-설계.md 가 <i>"권한이 없으면 null 이
 * 아니라 키 자체가 빠진다"</i> 로 정했고 자리표시자도 같은 형태다 (CM-08 · CM-11). {@code @JsonInclude(NON_NULL)} 이 그 한 필드에만
 * 붙는 이유는, 클래스에 붙이면 <b>앞으로 늘어날 null 가능 필드까지 조용히 사라지기</b> 때문이다 — 나머지는 「null 가능 필드는 생략하지 않고 null 로
 * 명시한다」가 기본이다.
 *
 * <p><b>{@code status} 를 함께 내린다.</b> {@code content} 가 없다는 것만으로는 화면이 「지워진 메시지입니다」를 그릴 근거가 없다 — 댓글
 * 응답도 같은 이유로 {@code status} 를 내린다.
 *
 * <p><b>보낸 사람 블록이 셋이다</b> ({@code userId} · {@code nickname} · {@code profileImageUrl}). 작성자 블록의 네
 * 번째 필드인 {@code lastSeen} 을 뺀 것은 메시지마다 같은 값이 반복되기 때문이고, 그 값이 필요한 자리는 방 상세의 멤버 목록이다 (CH-06).
 */
public record MessageItemResponse(
    @Schema(description = "메시지 번호", example = "51") Long messageId,
    @Schema(description = "보낸 사람") MessageSenderResponse sender,
    @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "본문. 지운 메시지면 이 키가 없다", example = "8시에 3번 출구에서 봬요")
        String content,
    @Schema(description = "ACTIVE 또는 DELETED", example = "ACTIVE") MessageStatus status,
    @Schema(description = "보낸 시각 (KST)", example = "2026-10-02T20:10:00+09:00")
        OffsetDateTime createdAt) {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  static MessageItemResponse from(MessageView view) {
    return new MessageItemResponse(
        view.messageId(),
        MessageSenderResponse.from(view),
        view.content(),
        view.status(),
        toKst(view.createdAt()));
  }

  /** 저장은 UTC, 응답은 KST 다 ({@code ChatMessageResponse#toKst} 와 같은 변환). */
  private static OffsetDateTime toKst(LocalDateTime storedInUtc) {
    return storedInUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toOffsetDateTime();
  }
}
