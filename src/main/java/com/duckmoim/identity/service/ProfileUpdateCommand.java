package com.duckmoim.identity.service;

/**
 * 프로필 수정 유스케이스의 입력 (AU-08).
 *
 * <p><b>{@code userId} 를 요청 본문에서 받지 않는다.</b> {@code @AuthenticationPrincipal} 에서 꺼내 여기 담는다 — 남의
 * 프로필을 고치는 경로를 만들지 않는 유일한 장치다. {@code SignupCommand} 와 같은 모양이다.
 *
 * <p>필드의 {@code null} 은 「안 건드림」이다. 그 규칙은 {@link com.duckmoim.identity.domain.Profile} 에 적어 두었다.
 */
public record ProfileUpdateCommand(Long userId, String nickname, String bio) {}
