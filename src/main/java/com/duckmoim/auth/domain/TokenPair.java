package com.duckmoim.auth.domain;

/**
 * 로그인·재발급 응답이 함께 내려주는 토큰 두 장.
 *
 * <p>둘을 따로 반환하면 한쪽만 내려주는 경로가 생긴다 — Refresh Rotation 은 <b>새 Access 와 새 Refresh 를 같이</b> 주는 것이
 * 규칙이라(AU-03) 한 객체로 묶는다.
 *
 * <p>Access 는 {@code Authorization: Bearer}, Refresh 는 <b>응답·요청 body</b> 로 오간다. 쿠키를 쓰지 않는다
 * (0001-토큰-전달-방식.md 「선택」).
 */
public record TokenPair(String accessToken, String refreshToken) {}
