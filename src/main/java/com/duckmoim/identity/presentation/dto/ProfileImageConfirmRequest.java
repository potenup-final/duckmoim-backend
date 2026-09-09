package com.duckmoim.identity.presentation.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 확정 요청 (AU-08).
 *
 * <p><b>주소가 아니라 키를 받는다.</b> 주소를 받으면 클라이언트가 임의 URL 을 박을 수 있고, 그 순간 결정 D-2(서버가 기본 이미지 URL 을 만들지 않는다)와
 * F 가 {@code profileImageUrl} 을 쓰기로 열지 않은 판단이 함께 무너진다.
 *
 * <p>키는 <b>서버가 발급 때 만든 것</b>이고 회원번호가 접두어에 들어 있어, 서버가 그것이 요청자의 것인지 검사한다.
 */
public record ProfileImageConfirmRequest(@NotBlank(message = "객체 키는 필수입니다.") String objectKey) {}
