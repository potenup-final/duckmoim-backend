package com.duckmoim.companion.presentation;

import com.duckmoim.companion.domain.PostStatus;
import com.duckmoim.companion.service.WrittenCompanionPost;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 방금 쓴 모집글의 응답.
 *
 * <p><b>조회 응답과 같은 모양이 아니다.</b> 화면-계약.md 「목록 `GET /api/v1/posts` — PO-08」의 모집글에는 {@code author} 블록과
 * {@code commentCount} 가 있는데, 전자는 회원 정보를 붙여야 나오고 후자는 조회 시점에 세는 값이다 (CM-12). 도메인-모델링.md 「7.1 가시성과
 * 권한」이 조립 지점을 한 곳으로 모으라고 정했고, 작성 응답에서 한 번 더 조립하면 두 곳이 갈라진다. 상세 조회(PO-11) 티켓이 그 한 곳을 만든다. {@code
 * CommentResponse} 가 같은 판단을 했다.
 *
 * <p><b>{@code closedReason} 도 담지 않는다.</b> 작성 직후에는 반드시 {@code null} 이라 담아도 알려주는 것이 없다. 상태는 항상
 * {@code OPEN} 이지만 그쪽은 남긴다 — 라이프사이클의 출발점이 무엇인지가 계약이다.
 *
 * @param eventId 고른 행사의 <b>외부 식별자</b>다. 행사를 안 골랐으면 {@code null} 이고 그때는 {@code eventTitle} · {@code
 *     eventImageUrl} 도 함께 {@code null} 이다
 * @param meetAt 저장은 UTC 이고 응답은 KST 오프셋을 포함한다 (API-컨벤션.md 「필드 표기 규칙」)
 * @param createdAt 같은 규칙이다
 */
public record CompanionPostResponse(
    Long id,
    String eventId,
    String eventTitle,
    String eventImageUrl,
    String title,
    String content,
    PostStatus status,
    Integer capacity,
    OffsetDateTime meetAt,
    MeetPointResponse meetPoint,
    OffsetDateTime createdAt) {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  static CompanionPostResponse from(WrittenCompanionPost written) {
    return new CompanionPostResponse(
        written.id(),
        written.eventId(),
        written.eventTitle(),
        written.eventImageUrl(),
        written.title(),
        written.content(),
        written.status(),
        written.capacity(),
        toKst(written.meetAt()),
        MeetPointResponse.from(written.meetPoint()),
        toKst(written.createdAt()));
  }

  private static OffsetDateTime toKst(LocalDateTime storedInUtc) {
    return storedInUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toOffsetDateTime();
  }
}
