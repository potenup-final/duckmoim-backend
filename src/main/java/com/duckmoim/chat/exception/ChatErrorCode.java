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
 * <p><b>쓰는 것만 있다.</b> 퇴장(CH-04)의 {@code CHAT_ROOM_HOST_CANNOT_LEAVE} 는 STAR-102 가 그 명령과 함께 더했다 — 미리
 * 지어내면 쓰지 않는 상수가 남고, 그 상수가 다음 담당의 기준선이 된다.
 *
 * <p><b>{@code CHAT_ROOM_ACCESS_DENIED} 는 404 가 아니라 403 이다.</b> API-설계.md 1장의 일반 원칙(「리소스의 존재 자체를
 * 숨겨야 하면 403 이 아니라 404」)과 반대 방향인데, CH-06 의 검증 기준이 「멤버가 아니면 403」으로 명시했다 — 요구사항이 명시적으로 정한 자리는 일반 원칙보다
 * 앞선다. CH-07 의 「비멤버 전송 시 403」도 같은 코드를 쓴다.
 *
 * <p><b>그래서 그 문구가 「조회할 수 있습니다」에서 「이용할 수 있습니다」로 바뀌었다</b> (STAR-111). CH-06 이 이 코드를 낼 때는 조회뿐이었지만 이제
 * 전송(CH-07)도 같은 코드로 막힌다 — 메시지를 못 보낸 사람에게 「조회할 수 있습니다」는 사실과 다른 안내다.
 *
 * <p><b>이미지 네 줄이 다 400 이다</b> (CH-14 · STAR-115). 검증 기준이 「허용 밖 형식·크기 400」 · 「업로드 확인 전 메시지 전송 시
 * 400」으로 상태를 직접 정했다. <b>없음을 404 로 가르지 않은 것이 그 줄을 따른 결과다</b> — 전송 시점에 「그 번호의 사진이 없다」와 「확인 전이다」와 「남이
 * 이미 썼다」를 갈라 답하면, 그 번호의 사진이 존재한다는 사실을 알려준다. AU-08 이 <i>"남의 키를 들고 오면 「올린 것이 없다」로 답한다"</i> 로 같은 판단을
 * 했다.
 *
 * <p><b>409 가 다섯이다.</b> 다섯 다 「대상은 있는데 지금 그 명령이 성립하지 않는다」 이고, 서로 다른 이름인 이유는 화면이 달리 답해야 하기 때문이다 — 이미
 * 멤버면 초대 버튼을 감추면 되고, 나간 사람이면 다시는 못 부른다는 것을 말해야 하고, 상한이면 방이 꽉 찬 것이라 초대 대상과 무관하고, 읽기 전용이면 입력창 자체를 닫아야
 * 하고, 방장이면 나가기 버튼을 애초에 보이지 않아야 한다.
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
  CHAT_ROOM_MEMBER_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "채팅방 인원이 상한에 찼습니다."),
  CHAT_ROOM_HOST_CANNOT_LEAVE(HttpStatus.CONFLICT, "방장은 채팅방을 나갈 수 없습니다."),
  CHAT_ROOM_ACCESS_DENIED(HttpStatus.FORBIDDEN, "채팅방 멤버만 이용할 수 있습니다."),
  CHAT_ROOM_READ_ONLY(HttpStatus.CONFLICT, "만남 후 7일이 지나 더 이상 메시지를 보낼 수 없습니다."),
  CHAT_CLIENT_MESSAGE_ID_REUSED(HttpStatus.CONFLICT, "이미 다른 메시지에 쓴 식별자입니다."),
  CHAT_MESSAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "메시지를 찾을 수 없습니다."),
  CHAT_MESSAGE_NOT_SENDER(HttpStatus.FORBIDDEN, "보낸 사람만 지울 수 있습니다."),
  CHAT_ROOM_NOT_REPORTED(HttpStatus.FORBIDDEN, "신고가 접수된 방만 열람할 수 있습니다."),
  CHAT_MESSAGE_NOT_ACTIVE(HttpStatus.CONFLICT, "이미 지워지거나 가려진 메시지입니다."),
  CHAT_MESSAGE_EMPTY(HttpStatus.BAD_REQUEST, "본문과 이미지 중 하나는 있어야 합니다."),
  CHAT_IMAGE_TYPE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "허용되지 않는 이미지 형식입니다."),
  CHAT_IMAGE_TOO_LARGE(HttpStatus.BAD_REQUEST, "이미지 용량이 허용 범위를 넘습니다."),
  CHAT_IMAGE_NOT_UPLOADED(HttpStatus.BAD_REQUEST, "업로드된 이미지를 찾을 수 없습니다."),
  CHAT_IMAGE_NOT_CONFIRMED(HttpStatus.BAD_REQUEST, "업로드 확인을 마친 이미지만 보낼 수 있습니다.");

  private final HttpStatus status;
  private final String message;

  @Override
  public String getCode() {
    return name();
  }
}
