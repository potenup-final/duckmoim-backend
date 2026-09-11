package com.duckmoim.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채팅방의 멤버.
 *
 * <p><b>{@link ChatRoom} 애그리게이트 안이다</b> (도메인-모델링.md 「3.1 경계와 트랜잭션 범위」). 경계 안이라 방을 객체로 참조한다 — ID 참조
 * 규칙은 애그리게이트끼리의 것이고, {@code Message} 가 {@code roomId} 로만 방을 아는 것과 여기가 다른 이유가 그것이다.
 *
 * <p><b>나가도 행을 지우지 않는다</b> (CH-04). I-19 의 이중 방어가 「퇴장 이력 조회」 이고 (CH-02a 재초대 차단), 행을 지우면 스스로 나간 것과
 * 초대받은 적 없는 것이 구분되지 않는다. 그래서 퇴장은 삭제가 아니라 {@code leftAt} 을 채우는 일이다.
 *
 * <p><b>{@code BaseEntity} 를 물려받지 않는다.</b> {@code joinedAt} 이 곧 생성 시각이고 갱신되는 값이 {@code leftAt} 하나라,
 * {@code createdAt}·{@code updatedAt} 을 두면 같은 사실을 두 번 적게 된다.
 *
 * <p><b>마지막 읽은 지점이 아직 없다.</b> 경계 안 목록에는 있지만 그 값의 타입이 API-설계.md 「3. 커서 정의」 에 채팅이 더해진 뒤에 정해진다 —
 * CH-13(안 읽은 수) 티켓이 더한다.
 */
@Entity
@Table(name = "chat_room_member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoomMember {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "room_id", nullable = false)
  private ChatRoom room;

  // 애그리게이트 밖은 ID 로만 참조한다. 멤버는 Identity 의 User 를 ID 로 안다 (도메인 2장).
  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "joined_at", nullable = false)
  private LocalDateTime joinedAt;

  @Column(name = "left_at")
  private LocalDateTime leftAt;

  private ChatRoomMember(ChatRoom room, Long userId) {
    this.room = room;
    this.userId = userId;
    this.joinedAt = LocalDateTime.now(ZoneOffset.UTC);
  }

  /**
   * 방에 들어온다.
   *
   * <p>수락 단계가 없어 초대가 곧 입장이다 (CH-02). 방을 열 때 방장이 지나는 자리도 여기다 — 방장이 유일한 멤버로 시작한다는 것(CH-01)이 「멤버 하나가
   * 들어와 있다」와 같은 뜻이라, 방장에게 다른 경로를 두지 않는다.
   */
  static ChatRoomMember joining(ChatRoom room, Long userId) {
    return new ChatRoomMember(room, userId);
  }

  /** 나가지 않은 멤버인가 (CH-18). */
  public boolean isJoined() {
    return leftAt == null;
  }
}
