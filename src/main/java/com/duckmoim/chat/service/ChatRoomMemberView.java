package com.duckmoim.chat.service;

import com.duckmoim.identity.domain.LastSeen;

/**
 * 방 상세의 멤버 한 명 (CH-06).
 *
 * <p>{@code nickname} · {@code profileImageUrl} · {@code lastSeen} 은 이미 {@link
 * com.duckmoim.identity.domain.AuthorDisplay} 를 지난 값이다 — 탈퇴한 멤버는 여기 오기 전에 자리표시자로 바뀐다 (AU-11).
 */
public record ChatRoomMemberView(
    Long userId, String nickname, String profileImageUrl, LastSeen lastSeen) {}
