package com.duckmoim.identity.service;

/**
 * 발급 결과 (AU-08).
 *
 * <p>{@code objectKey} 를 함께 준다 — 확정 요청이 그것을 들고 온다. <b>발급과 확정 사이의 상태를 서버가 저장하지 않는 이유가 이 필드다.</b> 키에
 * 회원번호가 들어 있어 확정 때 접두어로 소유자를 검사할 수 있다.
 */
public record ProfileImageUpload(String uploadUrl, String objectKey, long expiresInSeconds) {}
