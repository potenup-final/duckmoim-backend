package com.duckmoim.identity.presentation.dto;

import com.duckmoim.identity.service.ProfileImageUpload;

/**
 * 발급 응답 (AU-08).
 *
 * <p><b>{@code objectKey} 를 함께 준다.</b> 클라이언트가 업로드를 마친 뒤 확정 요청에 그것을 담아 보낸다 — 서버가 발급과 확정 사이의 상태를 저장하지
 * 않는 이유다.
 *
 * <p>{@code expiresInSeconds} 는 서명의 남은 수명이다. 사용자가 그 안에 올려야 하고, 지나면 다시 발급받는다.
 */
public record ProfileImageUploadResponse(
    String uploadUrl, String objectKey, long expiresInSeconds) {

  public static ProfileImageUploadResponse from(ProfileImageUpload upload) {
    return new ProfileImageUploadResponse(
        upload.uploadUrl(), upload.objectKey(), upload.expiresInSeconds());
  }
}
