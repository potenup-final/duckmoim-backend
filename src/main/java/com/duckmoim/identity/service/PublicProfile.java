package com.duckmoim.identity.service;

import com.duckmoim.identity.domain.LastSeen;
import com.duckmoim.identity.domain.User;
import java.time.Clock;

/**
 * {@code GET /users/{userId}} 가 돌려주는 것 (AU-09 · 화면 계약 3장).
 *
 * <p><b>노출 항목은 넷이고 {@code id} 가 함께 실린다</b> — 닉네임 · 프로필 이미지 · 한줄소개 · 최근 접속 구간. 명세서 AU-09 가 그 넷으로
 * 못박았다.
 *
 * <p><b>{@link MyProfile} 보다 좁다.</b> {@code signupCompleted} 와 {@code sanction} 이 없다 — 남의 가입 상태와 제재
 * 상태를 아무나 읽을 이유가 없다. 제재 사유는 <i>"본인에게 보여주는 정보"</i> 라 {@code /users/me} 의 것이다 (API 설계 4장).
 *
 * <p><b>출생연도가 없다.</b> AU-09 의 노출 항목에 없고 어느 응답에도 나가지 않는다. {@code kakaoUserId} 도 마찬가지다 (결정 D-2).
 *
 * <p><b>신고 가능 여부를 담지 않는다.</b> 화면 계약이 <i>"같은 필드를 둘지 화면이 로그인 유저와 비교할지 정해야 한다"</i> 로 열어 두었는데, 이 경로는
 * {@code PUBLIC} 이라 <b>요청자에 따라 응답이 갈리면 프론트가 캐시할 수 없다.</b> 클라이언트는 자기 {@code id} 를 {@code /users/me}
 * 로 이미 알고 있어서 {@code id} 비교로 판정할 수 있다.
 */
public record PublicProfile(
    Long id, String nickname, String profileImageUrl, String bio, LastSeen lastSeen) {

  static PublicProfile of(User user, Clock clock) {
    return new PublicProfile(
        user.getId(),
        user.getNickname(),
        user.getProfileImageUrl(),
        user.getBio(),
        LastSeen.from(user.getLastSeenAt(), clock));
  }
}
