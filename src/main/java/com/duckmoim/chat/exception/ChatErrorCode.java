package com.duckmoim.chat.exception;

import com.duckmoim.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 채팅 에러 코드 (API-설계.md 「4. 에러 코드」).
 *
 * <p><b>정본에 채팅 enum 이 아직 없다.</b> API-설계.md 가 1차 MVP 문서이고 채팅은 2차라, 표에 여섯 줄을 더하는 것이 아니라 <b>표가 열어 둔
 * 자리에 새 enum 을 낸다</b> — 「도메인별 enum 으로 나눈다. 중앙 집중 enum 은 두지 않는다」 가 그 규칙이다. 위키 반영은 별도 클론에서 따라온다.
 *
 * <p><b>이 티켓이 쓰는 것만 있다.</b> 퇴장(CH-04) · 전송(CH-07) · 채팅 가능 구간(CH-08)의 코드는 그 티켓이 더한다 — 미리 지어내면 쓰지 않는
 * 상수가 남고, 그 상수가 다음 담당의 기준선이 된다.
 *
 * <p><b>409 가 셋이다.</b> 셋 다 「대상은 있는데 지금 그 명령이 성립하지 않는다」 이고, 서로 다른 이름인 이유는 화면이 달리 답해야 하기 때문이다 — 이미
 * 멤버면 초대 버튼을 감추면 되고, 나간 사람이면 다시는 못 부른다는 것을 말해야 하고, 상한이면 방이 꽉 찬 것이라 초대 대상과 무관하다.
 *
 * <p><b>domain 이 아니라 여기 산다.</b> ErrorCode 가 HttpStatus 를 들고 있어 domain 에 두면 domain 이 Spring 에 의존하지
 * 않는다는 규칙과 정면으로 부딪힌다. CommentErrorCode 가 companion/exception 에 있는 것과 같은 배치다.
 */
@Getter
@RequiredArgsConstructor
public enum ChatErrorCode implements ErrorCode {
  CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."),
  CHAT_ROOM_NOT_HOST(HttpStatus.FORBIDDEN, "방장만 초대할 수 있습니다."),
  CHAT_INVITEE_NOT_COMMENTER(HttpStatus.BAD_REQUEST, "댓글을 쓴 사람만 초대할 수 있습니다."),
  CHAT_ALREADY_MEMBER(HttpStatus.CONFLICT, "이미 채팅방 멤버입니다."),
  CHAT_MEMBER_LEFT(HttpStatus.CONFLICT, "스스로 나간 사람은 다시 초대할 수 없습니다."),
  CHAT_ROOM_MEMBER_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "채팅방 인원이 상한에 찼습니다.");

  private final HttpStatus status;
  private final String message;

  @Override
  public String getCode() {
    return name();
  }
}
