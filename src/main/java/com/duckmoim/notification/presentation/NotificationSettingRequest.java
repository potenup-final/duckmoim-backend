package com.duckmoim.notification.presentation;

import com.duckmoim.common.domain.NotificationKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.EnumSet;
import java.util.Set;

/**
 * 종류별 수신 설정 저장 (NT-11).
 *
 * <p><b>셋을 다 받는다.</b> 계약이 {@code PUT}(전체 수정)이라 빠진 필드는 400 이다 — 부분 수정을 주면 「안 보낸 종류는 어떻게 되나」가 계약에 하나
 * 더 생긴다 (API-설계.md 「종류별 수신 설정 (NT-11)」).
 *
 * <p><b>{@code boolean} 이 아니라 {@code Boolean} 이다.</b> 원시 타입이면 빠진 필드가 {@code false} 로 조용히 채워져, <b>안
 * 보낸 종류가 꺼진다.</b> {@code @NotNull} 이 일을 하려면 null 이 될 수 있어야 한다.
 *
 * <p>필드 이름은 {@link NotificationKind} 값을 camelCase 로 옮긴 것이다 (API-컨벤션.md 「필드 표기 규칙」).
 */
public record NotificationSettingRequest(
    @Schema(description = "내 모집글에 댓글이 달렸을 때 받는다") @NotNull Boolean postCommented,
    @Schema(description = "내 댓글에 답글이 달렸을 때 받는다") @NotNull Boolean commentReplied,
    @Schema(description = "채팅방에 새 메시지가 있을 때 받는다") @NotNull Boolean roomMessaged) {

  /** 화면의 「켜짐 셋」을 저장의 「끈 종류」로 뒤집는다. */
  Set<NotificationKind> toMutedKinds() {
    Set<NotificationKind> muted = EnumSet.noneOf(NotificationKind.class);

    muteIfOff(muted, NotificationKind.POST_COMMENTED, postCommented);
    muteIfOff(muted, NotificationKind.COMMENT_REPLIED, commentReplied);
    muteIfOff(muted, NotificationKind.ROOM_MESSAGED, roomMessaged);

    return muted;
  }

  private static void muteIfOff(
      Set<NotificationKind> muted, NotificationKind kind, Boolean enabled) {

    if (!Boolean.TRUE.equals(enabled)) {
      muted.add(kind);
    }
  }
}
