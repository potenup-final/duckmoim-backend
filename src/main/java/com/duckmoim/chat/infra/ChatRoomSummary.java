package com.duckmoim.chat.infra;

import java.time.LocalDateTime;

/**
 * 방 목록 한 줄 (CH-05).
 *
 * <p><b>{@code lastMessage} · {@code unreadCount} 가 없다.</b> 명세가 요구하는 값이지만 {@code Message}(CH-07)와 안
 * 읽음 커서(CH-13)가 아직 없어 채울 데이터가 없다 — 그 티켓들이 이 프로젝션에 필드를 더한다.
 *
 * @param postTitle 모집글 제목. {@code eventTitle} 이 아니라 모집글 자체의 제목이다 — CH-05 는 「모집글의 만남 일시」만 요구하고 붙은 행사
 *     이름까지는 요구하지 않는다
 * @param memberCount 지금 방에 있는 인원. 모집글의 정원(`capacity`)과 무관하다 — CH-03 이 「모집글의 정원은 상한으로 쓰지 않는다」로 둘을
 *     분리했고, 「멤버가 방장뿐인 방도 목록에 있다」검증 기준도 실제 인원(1)을 세는 것을 전제로 한다
 */
public record ChatRoomSummary(
    Long roomId, Long postId, String postTitle, LocalDateTime meetAt, long memberCount) {}
