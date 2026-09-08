package com.duckmoim.auth.infra;

/**
 * 카카오 회원 조회 응답 중 <b>회원번호 하나</b>다.
 *
 * <p>실제 응답에는 {@code kakao_account} · {@code properties} 아래로 닉네임과 프로필 이미지가 들어올 수 있는데 <b>필드를 두지
 * 않는다.</b> 결정 D-2 가 카카오 프로필을 쓰지 않기로 했고, 필드가 있으면 「받아 두었으니 쓰자」로 흐른다. 받지 않으면 그 논의 자체가 생기지 않는다.
 */
record KakaoUserResponse(Long id) {}
