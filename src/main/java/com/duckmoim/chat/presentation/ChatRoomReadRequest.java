package com.duckmoim.chat.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 읽음 표시 요청 (CH-13).
 *
 * <p><b>클라이언트가 지점을 보낸다.</b> 서버가 「지금 이 방의 마지막 메시지」로 정하면, 화면에 반쯤 보인 상태에서 창을 닫아도 전부 읽은 것이 된다 — 어디까지
 * 보였는지는 화면만 안다.
 */
public record ChatRoomReadRequest(
    @Schema(description = "화면에서 마지막으로 본 메시지 번호", example = "51")
        @NotNull(message = "읽은 지점은 필수입니다.")
        @Positive(message = "읽은 지점은 1 이상이어야 합니다.")
        Long lastReadMessageId) {}
