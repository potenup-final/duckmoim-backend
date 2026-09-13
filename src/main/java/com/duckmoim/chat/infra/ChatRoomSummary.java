package com.duckmoim.chat.infra;

import java.time.LocalDateTime;

/**
 * 방 목록 한 줄 (CH-05).
 *
 * <p><b>{@code lastMessage} 는 아직 없다.</b> 명세가 요구하지만 목록 질의에 방마다 서브쿼리가 하나 더 붙고, 지우거나 가린 말풍선을 목록에서 어떻게
 * 보일지가 정해져 있지 않다 — 화면이 요구할 때 더한다.
 *
 * @param unreadCount 안 읽은 메시지 수 (CH-13). 내가 보낸 것은 빼고, 지우거나 가린 것은 센다 — 근거는 {@code
 *     ChatRoomQueryRepositoryImpl} 의 질의에 있다
 * @param postTitle 모집글 제목. {@code eventTitle} 이 아니라 모집글 자체의 제목이다 — CH-05 는 「모집글의 만남 일시」만 요구하고 붙은 행사
 *     이름까지는 요구하지 않는다
 * @param memberCount 지금 방에 있는 인원. 모집글의 정원(`capacity`)과 무관하다 — CH-03 이 「모집글의 정원은 상한으로 쓰지 않는다」로 둘을
 *     분리했고, 「멤버가 방장뿐인 방도 목록에 있다」검증 기준도 실제 인원(1)을 세는 것을 전제로 한다
 */
public record ChatRoomSummary(
    Long roomId,
    Long postId,
    String postTitle,
    LocalDateTime meetAt,
    long memberCount,
    long unreadCount) {}
