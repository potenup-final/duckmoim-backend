package com.duckmoim.identity.service;

/**
 * 발급 요청의 입력 (AU-08).
 *
 * <p><b>파일이 아니라 메타데이터다.</b> 브라우저가 S3 로 직접 올리므로 서버가 받는 것은 「무엇을 얼마나 올릴 것인가」 선언뿐이다.
 *
 * <p>{@code userId} 를 요청 본문에서 받지 않는다 — {@code @AuthenticationPrincipal} 에서 꺼낸다. 남의 프로필에 이미지를 박는 경로를
 * 만들지 않는 유일한 장치다.
 */
public record ProfileImageUploadCommand(Long userId, String contentType, long contentLength) {}
