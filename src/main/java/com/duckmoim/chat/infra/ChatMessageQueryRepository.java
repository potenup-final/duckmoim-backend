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
}
