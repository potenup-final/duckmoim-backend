package com.duckmoim.chat.presentation;

import com.duckmoim.chat.domain.MessageCursor;
import com.duckmoim.chat.service.MessageSlice;
import java.util.List;

/**
 * 메시지 목록 응답 (CH-09).
 *
 * <p>커서 페이지네이션 응답은 {@code items} · {@code nextCursor} · {@code hasNext} 셋이다 (API-컨벤션.md 「공통 응답
 * 형식」). 성공 응답을 {@code data} 로 감싸지 않는다.
 *
 * <p><b>{@code items} 는 최신순이다.</b> 화면은 아래가 최신이라 뒤집어 그리게 되는데, 목록을 오래된 순으로 내리면 <b>커서가 가리키는 방향과 응답 순서가
 * 반대</b>가 되어 이어 읽기가 헷갈린다. 다른 최신순 목록들과 같은 선택이다.
 */
public record MessageListResponse(
    List<MessageItemResponse> items, String nextCursor, boolean hasNext) {

  static MessageListResponse from(MessageSlice slice) {
    return new MessageListResponse(
        slice.items().stream().map(MessageItemResponse::from).toList(),
        encoded(slice.nextCursor()),
        slice.hasNext());
  }

  private static String encoded(MessageCursor cursor) {
    return cursor == null ? null : cursor.encode();
  }
}
