package com.duckmoim.chat.service;

import com.duckmoim.chat.domain.MessageCursor;
import java.util.List;

/**
 * 메시지 목록 한 페이지 (CH-09).
 *
 * <p>{@code Slice}·{@code Page} 를 service 시그니처에 노출하지 않는다는 규칙(아키텍처-컨벤션.md 「service」)에 따라 둔 결과 객체다.
 *
 * <p><b>{@code items} 는 {@code size} 만큼 온다.</b> 댓글 목록이 적게 올 수 있다고 밝힌 것은 자리표시자를 목록에서 빼기 때문인데 (CM-11)
 * 채팅은 반대다 — 지운 메시지가 자리표시자로 <b>남는다</b> (CH-12). 조회 뒤에 걸러 내는 것이 없다.
 *
 * @param nextCursor 마지막 페이지면 null 이다
 */
public record MessageSlice(List<MessageView> items, MessageCursor nextCursor, boolean hasNext) {}
