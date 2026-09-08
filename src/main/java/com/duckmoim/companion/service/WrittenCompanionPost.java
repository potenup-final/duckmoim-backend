package com.duckmoim.companion.service;

import com.duckmoim.companion.domain.Capacity;
import com.duckmoim.companion.domain.CompanionPost;
import com.duckmoim.companion.domain.MeetPoint;
import com.duckmoim.companion.domain.PostStatus;
import java.time.LocalDateTime;

/**
 * 방금 쓴 모집글.
 *
 * <p>엔티티를 그대로 presentation 으로 넘기지 않는다 (아키텍처-컨벤션.md 「service」).
 *
 * @param eventId 고른 행사의 <b>외부 식별자</b>다. 저장은 숫자 PK 로 하지만 응답에 나가는 것은 이쪽이다 (화면-계약.md 「목록 `GET
 *     /api/v1/posts` — PO-08」). 요청이 보낸 값을 그대로 돌려주므로 다시 조회하지 않는다
 * @param meetAt 저장된 값 그대로 UTC 다. KST 오프셋을 붙이는 것은 응답 조립의 일이다
 * @param createdAt 같은 이유로 UTC 다
 */
public record WrittenCompanionPost(
    Long id,
    String eventId,
    String eventTitle,
    String eventImageUrl,
    String title,
    String content,
    PostStatus status,
    Integer capacity,
    LocalDateTime meetAt,
    MeetPoint meetPoint,
    LocalDateTime createdAt) {

  static WrittenCompanionPost of(CompanionPost post, String eventExternalId) {
    return new WrittenCompanionPost(
        post.getId(),
        eventExternalId,
        post.getEventTitle(),
        post.getEventImageUrl(),
        post.getTitle(),
        post.getContent(),
        post.getStatus(),
        Capacity.valueOf(post.getCapacity()),
        post.getMeetAt(),
        post.getMeetPoint(),
        post.getCreatedAt());
  }
}
