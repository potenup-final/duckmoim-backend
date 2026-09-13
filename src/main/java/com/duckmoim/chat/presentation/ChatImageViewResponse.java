package com.duckmoim.chat.presentation;

import com.duckmoim.chat.service.ChatImageView;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 열람 서명 한 건 (CH-15).
 *
 * <p><b>메시지 번호가 함께 나간다.</b> 일괄 발급이라 어느 말풍선의 사진인지 말해 주지 않으면 화면이 붙일 자리를 모른다 — <b>볼 수 없는 것은 응답에서 빠지므로
 * 순서로 맞출 수 없다.</b>
 *
 * <p><b>객체 키도 이미지 번호도 없다.</b> 키가 나가면 위조할 값이 생기고, 이미지 번호는 목록에서 이미 받은 값이다.
 *
 * <p><b>남은 수명을 함께 내린다.</b> 화면이 이 값으로 다시 물어볼 때를 정한다. 없으면 만료된 주소로 사진을 그리려다 실패한 뒤에야 알게 되고, 그때 남는 것은 깨진
 * 이미지다. <b>캐시가 재사용한 주소는 설정값보다 짧다</b> — 그래서 고정값이 아니라 응답 필드다.
 */
public record ChatImageViewResponse(
    @Schema(description = "이 사진이 실린 메시지 번호", example = "51") Long messageId,
    @Schema(description = "이 주소로 브라우저가 직접 사진을 받는다. 수명이 지나면 끊긴다") String viewUrl,
    @Schema(description = "서명의 남은 수명(초)", example = "60") long expiresInSeconds) {

  static ChatImageViewResponse from(ChatImageView view) {
    return new ChatImageViewResponse(
        view.messageId(), view.signed().url(), view.signed().remaining().toSeconds());
  }
}
