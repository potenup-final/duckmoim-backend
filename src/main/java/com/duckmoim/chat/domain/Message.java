package com.duckmoim.chat.domain;

import com.duckmoim.chat.exception.ChatErrorCode;
import com.duckmoim.common.domain.BaseEntity;
import com.duckmoim.common.exception.BusinessException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 방에 보낸 한 마디 (CH-07).
 *
 * <p><b>{@code ChatRoom} 밖의 애그리게이트다</b> (도메인-모델링.md 「3.1 경계와 트랜잭션 범위」). 그 문서가 근거로 <i>"메시지 규칙 중 방을
 * 바꿔야 하는 것이 없다"</i> 를 들었고, {@code Comment} 를 {@code CompanionPost} 밖으로 뺀 것과 같은 기준이다. 그래서 {@code
 * roomId} 를 ID 로만 참조하고 객체 참조를 두지 않는다.
 *
 * <p><b>그 배치가 성능 결정이기도 하다.</b> 전송이 {@code chat_room} 행을 건드리지 않으므로 여럿이 동시에 보내도 서로 기다리지 않는다 — 읽은 지점을
 * 방이 아니라 멤버 행에 두기로 한 것(CH-13)과 같은 이유다.
 *
 * <p><b>본문 길이를 여기서 세지 않는다.</b> {@code Comment} 가 이미 정한 자리다 — API-컨벤션.md 「Validation 규칙」이 단순 형식 검증을
 * Bean Validation 으로 정했고 {@code chat_message.content} 가 {@code VARCHAR(1000)} 이라 그 둘을 지나지 않는 경로가
 * 없다. <b>같은 숫자를 세 곳에 두면 한 곳만 고치는 날이 온다.</b>
 *
 * <p><b>보낼 수 있는 때인지도 여기서 보지 않는다.</b> 판정 입력이 모집글의 만남시각이라 이 애그리게이트 밖이다 (CH-08 · I-21). {@link
 * ChatRoom#isWritable} 이 그 판정을 쥐고, 서비스가 모집글을 읽어 넘긴다 — {@code ChatRoomInviteService} 가 방장 여부를 그렇게
 * 다루는 것과 같은 배치다.
 *
 * <p><b>상태가 생겼다</b> (CH-12 · STAR-112). 도메인-모델링.md 6장의 전이 둘 중 삭제가 들어왔고 블라인드(AD-09)는 아직이다.
 */
@Entity
@Table(name = "chat_message")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Message extends BaseEntity {

  /**
   * 본문 상한 (CH-07).
   *
   * <p>이 값을 여기서 세지 않으면서 상수만 두는 이유는 <b>요청 DTO 의 {@code @Size} 가 이것을 가리키게 하려는 것</b>이다. 애너테이션 인자는 컴파일
   * 상수여야 해서 숫자를 직접 적게 되는데, 그러면 표의 {@code VARCHAR(1000)} 과 두 벌이 되고 둘이 갈려도 아무것도 깨지지 않는다.
   */
  public static final int MAX_CONTENT_LENGTH = 1000;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  // 애그리게이트 밖은 ID 로만 참조한다 (도메인-모델링.md 「3.2 애그리게이트 간 참조 규칙」).
  @Column(name = "room_id", nullable = false)
  private Long roomId;

  @Column(name = "sender_id", nullable = false)
  private Long senderId;

  /**
   * 클라이언트가 만든 식별자 (I-20).
   *
   * <p><b>재시도를 같은 것으로 알아보게 하는 유일한 값이다.</b> 전송 응답을 못 받은 클라이언트가 다시 보낼 때, 서버가 만든 번호로는 두 요청이 같은 메시지인지 알
   * 수 없다.
   *
   * <p>형식을 검증하지 않는다. 우리가 만든 값이 아니라 강제할 근거가 없고, {@code uq_chat_message_sender_client_id} 가 형식과 무관하게
   * 성립한다.
   */
  @Column(name = "client_message_id", nullable = false, length = 64)
  private String clientMessageId;

  @Column(name = "content", nullable = false, length = MAX_CONTENT_LENGTH)
  private String content;

  /**
   * 지웠는가 (CH-12).
   *
   * <p><b>본문을 비우지 않는다.</b> 조회에서 사라지는 것은 응답을 조립하는 쪽이 {@code status != ACTIVE} 를 보고 본문 키를 빼기 때문이고,
   * 본문이 남아야 신고(CH-21)와 관리자 열람(AD-08)이 판단 재료를 갖는다 — {@code Comment#blind} 가 같은 이유로 같은 것을 한다.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private MessageStatus status;

  /**
   * 함께 보낸 사진 (CH-14).
   *
   * <p><b>{@code ChatImage} 를 객체로 참조하지 않는다.</b> 그쪽은 이 애그리게이트 밖이다 — 업로드가 전송보다 앞이라 메시지 없이 먼저 존재하고, 그
   * 「메시지 없이 남은 것」이 {@code CH-17} 이 지우는 대상이다.
   *
   * <p><b>없는 것이 기본이다.</b> 이미지 없는 메시지가 대다수다.
   *
   * <p><b>주소가 아니라 번호다.</b> 받는 쪽이 볼 주소는 {@code CH-15} 가 방 멤버를 판정한 뒤 짧은 TTL 로 서명해 발급한다 — 여기에 주소를 박으면
   * 그 티켓이 저장된 값 전부를 고쳐야 한다 (계획서 8.2).
   */
  @Column(name = "image_id")
  private Long imageId;

  private Message(
      Long roomId, Long senderId, String clientMessageId, String content, Long imageId) {
    this.roomId = roomId;
    this.senderId = senderId;
    this.clientMessageId = clientMessageId;
    this.content = content;
    this.imageId = imageId;
    this.status = MessageStatus.ACTIVE;
  }

  /**
   * 메시지를 만든다 (CH-07).
   *
   * <p>멤버인지(I-18) · 보낼 수 있는 때인지(I-21)는 이 밖에서 판정한다. 둘 다 입력이 방과 모집글이라 이 애그리게이트가 답할 수 없다.
   */
  public static Message send(
      Long roomId, Long senderId, String clientMessageId, String content, Long imageId) {

    requireSomethingToSay(content, imageId);

    return new Message(roomId, senderId, clientMessageId, content, imageId);
  }

  /**
   * 빈 말은 없다 (CH-07 · CH-14).
   *
   * <p><b>이 불변식만 애그리게이트에 있다.</b> 본문 길이는 Bean Validation 과 컬럼 길이가 보는데 (위 {@code MAX_CONTENT_LENGTH}
   * 각주), 이것은 <b>두 필드에 걸쳐 있어</b> 애너테이션 하나로 표현할 수 없다. 요청 DTO 의 {@code @NotBlank} 를 본문에서 뺀 자리가 여기다 —
   * 사진만 보내는 메시지가 생겼기 때문이다.
   *
   * <p><b>본문은 {@code null} 로 들어오지 않는다.</b> 사진만 보내면 빈 문자열이다 — 컬럼이 {@code NOT NULL} 이고, 「빈 문자열」과 「없음」
   * 두 표현을 두면 조회 조립이 둘 다 다뤄야 한다 ({@code V704} 의 각주).
   */
  private static void requireSomethingToSay(String content, Long imageId) {
    if ((content == null || content.isBlank()) && imageId == null) {
      throw new BusinessException(ChatErrorCode.CHAT_MESSAGE_EMPTY);
    }
  }

  /** 사진이 실려 있는가 (CH-14). 조회 조립이 주소를 물을지 정하는 입력이다. */
  public boolean hasImage() {
    return imageId != null;
  }

  /**
   * 작성자가 지운다 (CH-12).
   *
   * <p><b>작성자 본인만이다. 방장도 못 지운다.</b> 댓글(CM-10)이 작성자와 방장 둘에게 준 것과 갈리는데, 명세의 상세가 「작성자 본인만」이고 방장에게 남의
   * 말을 지울 권한을 주기로 한 결정이 어디에도 없다. <b>부적절한 메시지는 신고(CH-21)와 블라인드(AD-09)로 간다</b> — 방 안의 권력이 아니라 운영이
   * 판단한다.
   *
   * <p><b>이미 지운 것을 다시 지우면 404 다.</b> API-컨벤션.md 「Validation 규칙」의 <i>"소프트 삭제된 리소스는 404 로 취급한다"</i> 를
   * 그대로 따른다. {@code Comment#blind} 가 409 를 쓴 것은 부르는 쪽이 관리자라 그 본문까지 읽을 수 있어 「없다」가 사실과 달랐기 때문이고, 여기는
   * 그 사정이 없다 — 지운 사람에게 그 메시지는 이미 없는 것이다.
   *
   * <p><b>방 멤버인지는 여기서 보지 않는다.</b> 방이 아는 사실이라 이 애그리게이트 밖이다 ({@code ChatRoom#isMember}). 서비스가 읽어 판정한다
   * — {@code ChatRoomInviteService} 가 방장 여부를 그렇게 다루는 것과 같은 배치다.
   */
  public void deleteBy(Long requesterId) {
    if (status != MessageStatus.ACTIVE) {
      throw new BusinessException(ChatErrorCode.CHAT_MESSAGE_NOT_FOUND);
    }

    if (!senderId.equals(requesterId)) {
      throw new BusinessException(ChatErrorCode.CHAT_MESSAGE_NOT_SENDER);
    }

    this.status = MessageStatus.DELETED;
  }

  /**
   * 신고 처리 결과로 관리자가 가린다 (AD-09).
   *
   * <p><b>409 인 이유는 {@code Comment#blind} 와 같다.</b> 부르는 쪽이 관리자라 그 메시지의 본문까지 읽을 수 있어 (AD-08) 「없다」로
   * 답하면 사실과 다르다. 지운 사람에게 404 를 주는 {@link #deleteBy} 와 갈리는 지점이 그것이다.
   *
   * <p><b>이미 지워진 메시지도 가릴 수 없다.</b> 도메인-모델링.md 「6. 라이프사이클」에서 {@code DELETED} 와 {@code BLINDED} 는 각각
   * 종착이고 둘 사이 전이가 없다 — 본문은 어느 쪽이든 이미 응답에서 빠진다.
   *
   * <p><b>보낸 사람인지 보지 않는다.</b> 관리자가 남의 메시지를 가리는 일이라 {@link #deleteBy} 의 판정이 여기 있으면 아무도 못 가린다.
   */
  public void blind() {
    if (status != MessageStatus.ACTIVE) {
      throw new BusinessException(ChatErrorCode.CHAT_MESSAGE_NOT_ACTIVE);
    }

    this.status = MessageStatus.BLINDED;
  }

  /** 본문을 응답에 실어도 되는가 (CH-12). 지운 메시지는 자리표시자만 남는다. */
  public boolean isVisible() {
    return status == MessageStatus.ACTIVE;
  }
}
