package com.duckmoim.chat.domain;

import com.duckmoim.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * <p><b>상태가 없다.</b> 도메인-모델링.md 6장의 {@code Message} 전이는 삭제(CH-12)와 블라인드(AD-09)인데 둘 다 다른 티켓이다. 지금 이
 * 애그리게이트가 지나는 상태는 「보냈다」 하나뿐이라 저장할 것이 없다.
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

  private Message(Long roomId, Long senderId, String clientMessageId, String content) {
    this.roomId = roomId;
    this.senderId = senderId;
    this.clientMessageId = clientMessageId;
    this.content = content;
  }

  /**
   * 메시지를 만든다 (CH-07).
   *
   * <p>멤버인지(I-18) · 보낼 수 있는 때인지(I-21)는 이 밖에서 판정한다. 둘 다 입력이 방과 모집글이라 이 애그리게이트가 답할 수 없다.
   */
  public static Message send(Long roomId, Long senderId, String clientMessageId, String content) {

    return new Message(roomId, senderId, clientMessageId, content);
  }
}
