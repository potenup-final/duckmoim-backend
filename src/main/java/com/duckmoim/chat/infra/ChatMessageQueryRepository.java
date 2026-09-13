package com.duckmoim.chat.infra;

import com.duckmoim.chat.domain.MessageListQuery;
import java.util.List;
import java.util.Optional;

/**
 * 메시지 목록 조회 (CH-09).
 *
 * <p>{@code ChatMessageRepository} 가 상속하고 {@code ChatMessageQueryRepositoryImpl} 이 구현한다 — Spring
 * Data 의 커스텀 구현 규약이다 ({@code ChatRoomQueryRepository} 와 같은 배치).
 */
public interface ChatMessageQueryRepository {

  /**
   * 한 방의 메시지를 최신부터 한 페이지 읽는다.
   *
   * <p><b>{@code size + 1} 건을 읽는다.</b> 다음 페이지가 있는지를 OFFSET 이나 COUNT 없이 판정하기 위해서다 — CH-09 의 검증 기준이
   * 「OFFSET 미사용」이다.
   */
  List<AuthoredMessage> findSlice(MessageListQuery query);

  /**
   * 한 건을 보낸 사람 정보와 함께 읽는다 (CH-10).
   *
   * <p>팬아웃에 실을 {@code MessageEvent} 를 만들려면 닉네임·아바타가 필요한데, 전송 결과({@code SentMessage})에는 그 값이 없다 —
   * {@code Message} 가 {@code User} 를 {@code senderId} 로만 참조하기 때문이다.
   *
   * <p><b>목록 질의와 같은 조인을 쓴다.</b> 두 경로가 다른 방식으로 같은 값을 만들면, 실시간으로 뜬 말풍선과 새로고침해서 뜬 말풍선이 갈릴 수 있다.
   */
  Optional<AuthoredMessage> findAuthoredById(Long messageId);

  /**
   * 한 방의 메시지를 주어진 번호 <b>뒤</b>부터 오래된 것부터 읽는다 (CH-11).
   *
   * <p><b>부등호와 정렬이 {@link #findSlice} 와 반대다.</b> 목록은 최신부터 거슬러 올라가고 ("{@code id < 커서}" · {@code
   * DESC}) 이쪽은 끊긴 지점부터 따라잡는다 ("{@code id > 커서}" · {@code ASC}). 같은 표를 두 방향으로 읽는 것이라 메서드를 나눈다 —
   * {@code MessageCursor} 가 <i>"모양이 같은 커서가 생겨도 타입을 돌려 쓰지 않는다"</i> 고 적은 것과 같은 이유다.
   *
   * <p><b>{@code ASC} 여야 한다.</b> 재전송은 선로에 시간순으로 실려야 하고, 끊길 경우 앞에서부터 받은 만큼이 남아야 다음 재연결이 그 뒤를 잇는다.
   *
   * <p><b>지운 메시지를 거르지 않는다.</b> 목록과 같다 (CH-12) — 끊겨 있는 동안 지워진 메시지도 자리표시자로 와야 그 자리가 목록과 맞는다.
   *
   * @param afterMessageId 이 번호는 포함하지 않는다
   * @param limit 넘겨받은 만큼만 읽는다. 넘치는지 판정하려면 상한 + 1 을 넣는다
   */
  List<AuthoredMessage> findAfter(Long roomId, Long afterMessageId, int limit);
}
