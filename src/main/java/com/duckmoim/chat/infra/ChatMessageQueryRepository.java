package com.duckmoim.chat.infra;

import com.duckmoim.chat.domain.MessageListQuery;
import java.util.List;

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
}
