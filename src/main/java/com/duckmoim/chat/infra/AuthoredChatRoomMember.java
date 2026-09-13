package com.duckmoim.chat.infra;

import com.duckmoim.identity.domain.AuthorDisplay;
import com.duckmoim.identity.domain.SignupStatus;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 방 멤버 한 명과 그 유저 정보를 함께 읽은 결과 (CH-06).
 *
 * <p>{@code ChatRoomMember} 는 {@code User} 를 {@code userId} 로만 참조한다 (도메인-모델링.md 「3.2 애그리게이트 간 참조
 * 규칙」). 응답의 멤버 목록에는 닉네임 · 아바타가 필요해 조회 시점에 조인한다 — {@code AuthoredPost} 와 같은 근거다.
 *
 * <p><b>탈퇴한 멤버는 여기서 걸러내지 않는다.</b> 나간 사람과 달리 탈퇴한 사람은 여전히 「멤버」다 — CH-18 이 구분하는 것은 방을 나간 것이지 회원 탈퇴가
 * 아니다. 대신 {@link #display} 가 {@code AuthorDisplay}(AU-11)로 자리표시자를 만든다.
 *
 * @param status 익명화 판정의 입력이다. {@code AuthoredPost} 와 같은 이유로 {@code nickname == null} 로 대신하지 않는다
 */
public record AuthoredChatRoomMember(
    Long userId,
    String nickname,
    String profileImageUrl,
    LocalDateTime lastSeenAt,
    SignupStatus status) {

  /** 응답에 나갈 멤버 블록으로 줄인다. 인자 순서를 틀릴 자리를 한 번만 남긴다 ({@code AuthoredPost#author} 와 같은 이유). */
  public AuthorDisplay display(Clock clock) {
    return AuthorDisplay.of(status, nickname, profileImageUrl, lastSeenAt, clock);
  }
}
