package com.duckmoim.notification.presentation;

import com.duckmoim.common.domain.NotificationKind;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;

/**
 * 종류별 수신 설정 (NT-11).
 *
 * <p><b>끈 것만 내려주지 않는다.</b> 화면이 토글 셋을 그리므로 셋 다 있어야 하고, 없는 것을 「켜짐」으로 조립하게 하면 그 규칙이 서버와 화면 두 곳에 생긴다
 * (API-설계.md 「종류별 수신 설정 (NT-11)」).
 *
 * <p><b>한 번도 만지지 않은 사람은 셋 다 {@code true} 다.</b> 저장된 것이 없다는 사실이 그대로 기본값이 된다.
 *
 * <p>boolean 에 {@code is} 를 붙이지 않는 것은 API 컨벤션이다.
 */
public record NotificationSettingResponse(
    @Schema(description = "내 모집글에 댓글이 달렸을 때 받는다") boolean postCommented,
    @Schema(description = "내 댓글에 답글이 달렸을 때 받는다") boolean commentReplied,
    @Schema(description = "채팅방에 새 메시지가 있을 때 받는다") boolean roomMessaged) {

  /** 저장의 「끈 종류」를 화면의 「켜짐 셋」으로 뒤집는다. */
  static NotificationSettingResponse from(Set<NotificationKind> mutedKinds) {
    return new NotificationSettingResponse(
        !mutedKinds.contains(NotificationKind.POST_COMMENTED),
        !mutedKinds.contains(NotificationKind.COMMENT_REPLIED),
        !mutedKinds.contains(NotificationKind.ROOM_MESSAGED));
  }
}
