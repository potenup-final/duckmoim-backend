package com.duckmoim.auth.infra;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 카카오 토큰 발급 응답 중 <b>우리가 쓰는 한 필드</b>다.
 *
 * <p>실제 응답에는 {@code refresh_token} · {@code expires_in} · {@code scope} 가 더 있는데 받지 않는다. 카카오 토큰은
 * 회원번호를 읽는 순간에만 쓰고 버리므로(결정 D-2) 갱신할 일이 없고, 저장하지 않는 값을 필드로 두면 나중에 「저장돼 있나?」를 되짚게 된다.
 *
 * <p>필드명이 스네이크 케이스라 {@link JsonProperty} 로 명시한다. 프로퍼티 하나만 매핑하려고 전역 네이밍 전략을 바꾸면 우리 API 응답까지 따라 바뀐다.
 */
record KakaoTokenResponse(@JsonProperty("access_token") String accessToken) {}
