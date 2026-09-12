package com.duckmoim.chat.infra;

import com.duckmoim.chat.domain.MessageStatus;
import com.duckmoim.identity.domain.AuthorDisplay;
import com.duckmoim.identity.domain.SignupStatus;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 메시지 한 건과 보낸 사람 정보를 함께 읽은 결과 (CH-09).
 *
 * <p>{@code Message} 는 {@code User} 를 {@code senderId} 로만 참조한다 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」).
 * 응답의 말풍선에는 닉네임 · 아바타가 필요해 조회 시점에 조인한다 — {@code AuthoredChatRoomMember} 와 같은 근거다.
 *
 * <p><b>방 상세의 멤버 목록으로 클라이언트가 맞추게 두지 않는다.</b> 그 목록은 <b>나가지 않은 멤버</b>만 담는데 (CH-18) 나간 사람이 남긴 옛 메시지는
 * 그대로 목록에 있다. 클라이언트가 맞추게 하면 그 말풍선이 이름 없이 뜬다.
 *
 * <p><b>탈퇴한 사람도 걸러내지 않는다.</b> {@link #display} 가 {@code AuthorDisplay}(AU-11)로 자리표시자를 만든다.
 *
 * @param roomId 팬아웃 채널을 고르는 값이다 (CH-10). 목록 조회는 이미 방을 알고 있어 쓰지 않는다
 * @param imageId 함께 보낸 사진 (CH-14). 없으면 {@code null} 이다
 * @param status 지운 메시지인지 (CH-12). 본문 키를 뺄지가 이 값으로 갈린다
 * @param senderStatus 익명화 판정의 입력이다. {@code nickname == null} 로 대신하지 않는다
 */
public record AuthoredMessage(
    Long messageId,
    Long roomId,
    Long senderId,
    String nickname,
    String profileImageUrl,
    LocalDateTime lastSeenAt,
    SignupStatus senderStatus,
    String content,
    Long imageId,
    MessageStatus status,
    LocalDateTime createdAt) {

  /** 응답에 나갈 보낸 사람 블록으로 줄인다. 인자 순서를 틀릴 자리를 한 번만 남긴다. */
  public AuthorDisplay display(Clock clock) {
    return AuthorDisplay.of(senderStatus, nickname, profileImageUrl, lastSeenAt, clock);
  }

  /** 본문을 응답에 실어도 되는가 (CH-12). 지운 메시지는 자리표시자만 남는다. */
  public boolean isVisible() {
    return status == MessageStatus.ACTIVE;
  }
}
