package com.duckmoim.identity.presentation.dto;

import com.duckmoim.identity.service.ProfileImageUploadCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 발급 요청 (AU-08).
 *
 * <p><b>파일이 아니라 선언이다.</b> 브라우저가 S3 로 직접 올리므로 서버가 받는 것은 「무엇을 얼마나 올릴 것인가」뿐이다.
 *
 * <p>여기 걸린 제약은 <b>형태</b>만 본다 — 비었는지, 양수인지. 허용 형식과 상한 판정은 설정에서 오므로 도메인 ({@code ProfileImagePolicy})이
 * 한다. Bean Validation 에 목록을 박으면 설정을 바꿔도 어노테이션이 따라오지 않는다.
 */
public record ProfileImageUploadRequest(
    @NotBlank(message = "이미지 형식은 필수입니다.") String contentType,
    @Positive(message = "이미지 크기는 0보다 커야 합니다.") long contentLength) {

  public ProfileImageUploadCommand toCommand(Long userId) {
    return new ProfileImageUploadCommand(userId, contentType, contentLength);
  }
}
