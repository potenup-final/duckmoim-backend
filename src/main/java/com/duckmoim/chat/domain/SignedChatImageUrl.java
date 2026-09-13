package com.duckmoim.chat.domain;

import java.time.Duration;

/**
 * 서명된 열람 주소와 그 주소가 살아 있는 시간 (CH-15).
 *
 * <p><b>남은 수명을 주소와 함께 내리는 것이 요점이다.</b> 화면은 이 값으로 언제 다시 물어볼지 정한다 — 없으면 만료된 주소로 사진을 그리려다 실패한 뒤에야 알게
 * 되고, 그때 화면에 남는 것은 깨진 이미지다.
 *
 * <p><b>발급 시각이 아니라 남은 시간이다.</b> 캐시가 재사용한 주소는 <b>이미 얼마간 쓰인 것</b>이라 수명이 발급 설정값과 다르다. 클라이언트 시계와 서버 시계가
 * 어긋나도 상대값은 그대로라는 것이 덤이다.
 */
public record SignedChatImageUrl(String url, Duration remaining) {}
