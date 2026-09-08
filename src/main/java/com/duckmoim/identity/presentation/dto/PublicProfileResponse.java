package com.duckmoim.identity.presentation.dto;

import com.duckmoim.identity.domain.LastSeen;
import com.duckmoim.identity.service.PublicProfile;

/**
 * 공개 프로필 응답 (AU-09 · 화면 계약 3장).
 *
 * <p>필드 다섯이 화면 계약의 예시 그대로다 — {@code id} · {@code nickname} · {@code profileImageUrl} · {@code bio}
 * · {@code lastSeen}.
 *
 * <p><b>{@code null} 을 생략하지 않는다.</b> 업로드하지 않은 유저의 {@code profileImageUrl} 과 한줄소개를 안 쓴 유저의 {@code
 * bio} 는 {@code null} 로 나간다 (API 컨벤션 「DTO 규칙」). 키를 지우는 것은 <b>권한에 따라 서버가 감추는 필드</b>일 때이고, 여기 없는 값은
 * 감춘 것이 아니라 비어 있는 것이다.
 *
 * <p><b>{@code lastSeen} 도 {@code null} 이 될 수 있다.</b> 재발급을 한 번도 하지 않은 계정은 접속이 관측된 적이 없다 ({@code
 * LastSeen.from} javadoc). 없는 것을 {@code LONG_AGO} 로 적으면 방금 가입한 사람이 한 달 넘게 활동이 없는 것으로 보인다.
 */
public record PublicProfileResponse(
    Long id, String nickname, String profileImageUrl, String bio, LastSeen lastSeen) {

  public static PublicProfileResponse from(PublicProfile profile) {
    return new PublicProfileResponse(
        profile.id(),
        profile.nickname(),
        profile.profileImageUrl(),
        profile.bio(),
        profile.lastSeen());
  }
}
