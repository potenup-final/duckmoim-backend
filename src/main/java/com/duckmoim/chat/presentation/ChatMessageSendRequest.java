package com.duckmoim.chat.presentation;

import com.duckmoim.chat.domain.Message;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 전송 요청 (CH-07 · CH-14).
 *
 * <p>보낸 사람을 본문으로 받지 않는다. 토큰에서 나온다 — 남의 이름으로 말하는 것을 막는 유일한 장치다 ({@code ChatRoomInviteRequest} 와 같은
 * 이유).
 *
 * <p><b>본문의 {@code @NotBlank} 를 뺐다</b> (CH-14 · STAR-115). 사진만 보내는 메시지가 생겼기 때문이다. 그렇다고 아무것도 없는 메시지를
 * 허용하는 것은 아니고, <b>「본문과 사진 중 하나는 있어야 한다」가 {@code Message#send} 로 내려갔다</b> — 두 필드에 걸친 조건이라 애너테이션 하나로
 * 표현할 수 없다. 상한(1000자)은 그대로 여기서 본다.
 */
public record ChatMessageSendRequest(
    @Schema(
            description = "클라이언트가 만든 식별자. 같은 값으로 다시 보내면 새로 저장하지 않고 먼저 보낸 것을 그대로 돌려준다",
            example = "9f1c2b7e-3a4d-4f10-9c2e-1b7d5a0e6c33")
        @NotBlank(message = "클라이언트 식별자는 필수입니다.")
        @Size(max = 64, message = "클라이언트 식별자는 64자를 넘을 수 없습니다.")
        String clientMessageId,
    @Schema(description = "본문. 최대 1000자. 사진만 보내면 생략한다", example = "8시에 3번 출구에서 봬요")
        @Size(max = Message.MAX_CONTENT_LENGTH, message = "본문은 1000자를 넘을 수 없습니다.")
        String content,
    @Schema(description = "함께 보낼 사진의 번호. 업로드 확정을 마친 것만 실을 수 있다", example = "7", nullable = true)
        Long imageId) {

  /**
   * 본문을 {@code null} 로 내려보내지 않는다.
   *
   * <p>컬럼이 {@code NOT NULL} 이고, 「빈 문자열」과 「없음」 두 표현을 두면 조회 조립이 둘 다 다뤄야 한다 ({@code V704} 의 각주). 사진만
   * 보내면 빈 문자열이 저장된다.
   */
  String contentOrEmpty() {
    return content == null ? "" : content;
  }
}
