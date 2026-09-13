package com.duckmoim.chat.service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 방 상세로 조립한 결과 (CH-06).
 *
 * @param writable 지금 메시지를 쓸 수 있는가 — 만남시각 + 7일 이내다 (CH-08). 전송을 막는 409 자체는 CH-08 티켓의 몫이고, 여기는 화면이
 *     입력창을 보여줄지만 정한다
 */
public record ChatRoomDetailView(
    Long roomId,
    Long postId,
    String postTitle,
    LocalDateTime meetAt,
    List<ChatRoomMemberView> members,
    boolean writable) {}
