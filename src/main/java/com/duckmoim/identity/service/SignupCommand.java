package com.duckmoim.identity.service;

/**
 * 가입 정보 입력 커맨드 (AU-05).
 *
 * <p><b>{@code userId} 를 요청 본문으로 받지 않는다.</b> 컨트롤러가 {@code @AuthenticationPrincipal} 에서 꺼내 넣는다 — 남의
 * 가입 정보를 채우는 경로를 만들지 않는 유일한 장치다.
 */
public record SignupCommand(Long userId, String nickname, int birthYear) {}
