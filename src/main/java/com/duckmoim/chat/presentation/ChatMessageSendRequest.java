package com.duckmoim.chat.presentation;

import com.duckmoim.chat.domain.Message;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 전송 요청 (CH-07).
 *
 * <p>보낸 사람을 본문으로 받지 않는다. 토큰에서 나온다 — 남의 이름으로 말하는 것을 막는 유일한 장치다 ({@code ChatRoomInviteRequest} 와 같은
 * 이유).
 */
public record ChatMessageSendRequest(
    @Schema(
            description = "클라이언트가 만든 식별자. 같은 값으로 다시 보내면 새로 저장하지 않고 먼저 보낸 것을 그대로 돌려준다",
            example = "9f1c2b7e-3a4d-4f10-9c2e-1b7d5a0e6c33")
        @NotBlank(message = "클라이언트 식별자는 필수입니다.")
        @Size(max = 64, message = "클라이언트 식별자는 64자를 넘을 수 없습니다.")
        String clientMessageId,
    @Schema(description = "본문. 최대 1000자", example = "8시에 3번 출구에서 봬요")
        @NotBlank(message = "본문은 필수입니다.")
        @Size(max = Message.MAX_CONTENT_LENGTH, message = "본문은 1000자를 넘을 수 없습니다.")
        String content) {}
