package com.duckmoim.chat.infra;

import com.duckmoim.chat.domain.MessageEvent;
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

  /**
   * 선로에 실릴 사건으로 바꾼다 (CH-10 · CH-11).
   *
   * <p><b>팬아웃과 재전송이 같은 자리에서 사건을 만든다.</b> 각자 만들면 실시간으로 뜬 말풍선과 재연결해서 온 말풍선이 갈리는데, 그 둘은 같은 화면의 같은 배열에
   * 들어간다.
   *
   * <p><b>탈퇴 익명화를 여기서 태우는 이유가 재전송에 있다</b> (AU-11). 팬아웃은 방금 보낸 사람의 메시지라 탈퇴자를 만날 수 없지만, 재전송은 몇 시간 전
   * 것을 읽어 <b>그 사이 탈퇴한 사람을 만난다.</b> 목록 조회({@code ChatMessageQueryService})가 이미 {@link #display} 로 같은
   * 판정을 하고 있어, 여기서 빼면 실시간 경로로만 실명이 남는다.
   *
   * <p><b>지운 메시지의 본문을 싣지 않는다</b> (CH-12). 응답을 그리는 자리가 한 번 더 끊지만 (<i>{@code
   * MessageItemResponse#from}</i>) <b>본문이 Redis 를 지나가지 않는 편이 낫다</b> — 팬아웃 payload 는 이 프로세스 밖으로 나간다.
   */
  public MessageEvent toEvent(Clock clock) {
    AuthorDisplay sender = display(clock);

    return new MessageEvent(
        messageId,
        roomId,
        senderId,
        sender.nickname(),
        sender.profileImageUrl(),
        isVisible() ? content : null,
        status,
        createdAt);
  }
}
