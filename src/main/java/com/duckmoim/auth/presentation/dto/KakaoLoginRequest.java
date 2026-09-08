package com.duckmoim.auth.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 카카오 로그인 요청 (AU-01).
 *
 * <p><b>{@code redirectUri} 를 함께 받는다.</b> 카카오 토큰 API 가 인가 때 쓴 값과 <b>같은 값</b>을 요구하는데, 그 값은 브라우저의
 * {@code origin + /auth/kakao/callback} 이라 <b>환경마다 다르다</b> — 로컬 · 프리뷰 · 프로덕션이 각각이다. 서버 설정값 하나로 두면
 * 백엔드 한 대가 프론트 한 곳만 상대할 수 있고, 프리뷰에서는 로그인이 성립하지 않는다.
 *
 * <p><b>클라이언트가 보낸 값이라 위험하지 않은 이유</b> — 이 값은 우리가 리다이렉트하는 데 쓰이지 않는다. 카카오가 자기 콘솔에 등록된 주소와 대조해서 다르면
 * 거절하므로(KOE006), 등록되지 않은 주소를 넣어도 토큰이 나오지 않는다. OAuth 의 리다이렉트 공격은 인가 단계의 문제이고 이 요청은 그 뒤의 교환 단계다.
 *
 * <p>허용 목록을 서버에 따로 두지 않는다. 카카오 콘솔이 이미 그 목록이라, 여기 또 두면 프리뷰 주소가 한쪽에만 등록돼 원인을 두 곳에서 찾게 된다. 형식만 본다 —
 * 카카오까지 왕복하고 나서야 오타를 알게 되는 것을 막는다.
 */
public record KakaoLoginRequest(
    @NotBlank(message = "인가코드는 필수입니다.") String code,
    @NotBlank(message = "리다이렉트 주소는 필수입니다.")
        @Pattern(regexp = "^https?://\\S+$", message = "리다이렉트 주소 형식이 올바르지 않습니다.")
        String redirectUri) {}
