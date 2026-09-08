package com.duckmoim.identity.service;

import com.duckmoim.identity.domain.LastSeen;
import com.duckmoim.identity.domain.User;
import java.time.Clock;

/**
 * {@code GET /users/me} 가 돌려주는 것 (API-설계.md 2-2).
 *
 * <p>담기는 것은 넷이다 — 프로필({@code id}·{@code nickname}·{@code profileImageUrl}·{@code bio}) · {@code
 * signupCompleted} · {@code lastSeen}(구간 값) · {@code sanction}.
 *
 * <p><b>{@code lastSeenAt} 원본을 담지 않는다.</b> 도메인 7.2 가 <i>"본인 조회에서도 동일하게 구간으로 내린다"</i> 고 정했다 — 낯선 사람과
 * 만나는 서비스라 정확한 시각은 활동 패턴 추적이 된다. 본인이라고 예외를 두면 경로마다 형태가 갈린다.
 *
 * <p><b>{@code birthYear} 를 담지 않는다.</b> API 설계 2-2 의 목록에 없다 — 저장만 하고 어디에도 내보내지 않는다.
 */
public record MyProfile(
    Long id,
    String nickname,
    String profileImageUrl,
    String bio,
    boolean signupCompleted,
    LastSeen lastSeen,
    SanctionView sanction) {

  static MyProfile of(User user, SanctionView sanction, Clock clock) {
    return new MyProfile(
        user.getId(),
        user.getNickname(),
        user.getProfileImageUrl(),
        user.getBio(),
        user.isSignupCompleted(),
        LastSeen.from(user.getLastSeenAt(), clock),
        sanction);
  }
}
