package com.duckmoim.notification.presentation;

import com.duckmoim.common.domain.NotificationKind;
import com.duckmoim.notification.service.NotificationView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 알림 한 건 (NT-06 · NT-08).
 *
 * <p><b>문구가 없다.</b> {@code kind} 와 참조 ID 를 내려주고 화면이 조립한다 (API-설계.md 「2-10. 알림 (Notification) ·
 * 2차」). 서버가 문구를 쥐면 문안 한 줄 고치는 데 배포가 든다.
 *
 * <p><b>{@code readAt} 이 아니라 {@code read} 다.</b> 화면이 쓰는 것은 「읽었나」 하나이고, 시각은 나중에 되돌아볼 때를 위해 표에만 남는다.
 * boolean 에 {@code is} 를 붙이지 않는 것은 API 컨벤션이다.
 *
 * <p><b>참조 ID 는 종류마다 다른 칸이 찬다.</b> 댓글 알림은 모집글·댓글이고 채팅 알림은 방·메시지다. 해당 없는 쪽을 빼지 않고 {@code null} 로 내리는
 * 것은 <b>종류마다 응답 모양이 달라지면 화면이 타입을 둘로 쥐게 되기</b> 때문이다 — 문구를 조립하려면 어차피 {@code kind} 로 먼저 갈라 읽는다
 * (API-설계.md 「2-10. 알림 (Notification) · 2차」).
 *
 * @param postId 알림을 눌렀을 때 갈 모집글. 채팅 알림이면 비어 있다
 * @param commentId 그 모집글에서 가리킬 댓글. 채팅 알림이면 비어 있다
 * @param roomId 알림을 눌렀을 때 갈 채팅방. 댓글 알림이면 비어 있다
 * @param messageId 그 방에서 가리킬 메시지. 댓글 알림이면 비어 있다
 * @param createdAt 저장은 UTC 이고 응답은 KST 오프셋을 포함한다 (API-컨벤션.md 「필드 표기 규칙」)
 */
public record NotificationItemResponse(
    @Schema(description = "알림 번호") Long id,
    @Schema(description = "알림 종류") NotificationKind kind,
    @Schema(description = "대상 모집글") Long postId,
    @Schema(description = "대상 댓글") Long commentId,
    @Schema(description = "대상 채팅방") Long roomId,
    @Schema(description = "대상 메시지") Long messageId,
    @Schema(description = "읽었는지") boolean read,
    @Schema(description = "알림이 생긴 시각") OffsetDateTime createdAt) {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  static NotificationItemResponse from(NotificationView view) {
    return new NotificationItemResponse(
        view.id(),
        view.kind(),
        view.postId(),
        view.commentId(),
        view.roomId(),
        view.messageId(),
        view.read(),
        toKst(view.createdAt()));
  }

  /**
   * 저장된 UTC 값에 KST 오프셋을 달아 내보낸다.
   *
   * <p>변환을 여기서 하는 것은 시각 표기가 <b>화면 계약</b>이기 때문이다. service 가 UTC 로 넘기고 (NotificationView) 표기는
   * presentation 이 정한다 — 감사 로그 응답이 같은 자리에서 같은 변환을 한다.
   */
  private static OffsetDateTime toKst(LocalDateTime storedInUtc) {
    return storedInUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toOffsetDateTime();
  }
}
