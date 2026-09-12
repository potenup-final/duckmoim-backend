package com.duckmoim.chat.presentation;

import com.duckmoim.chat.service.ChatImageUpload;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 서명 발급 응답 (CH-14).
 *
 * <p><b>객체 키가 없다.</b> 클라이언트가 쥐는 손잡이는 {@code imageId} 하나다 — 키가 클라이언트를 지나지 않으면 위조할 값이 없고, 확정·전송의 소유자
 * 판정은 DB 행이 한다 ({@code ChatImageUpload} 의 각주).
 */
public record ChatImageUploadResponse(
    @Schema(description = "확정과 전송에 쓰는 이미지 번호", example = "7") Long imageId,
    @Schema(description = "이 주소로 브라우저가 직접 PUT 한다. 서버는 바이트를 받지 않는다") String uploadUrl,
    @Schema(description = "서명의 남은 수명(초)", example = "300") long expiresInSeconds) {

  static ChatImageUploadResponse from(ChatImageUpload upload) {
    return new ChatImageUploadResponse(
        upload.imageId(), upload.uploadUrl(), upload.expiresInSeconds());
  }
}
