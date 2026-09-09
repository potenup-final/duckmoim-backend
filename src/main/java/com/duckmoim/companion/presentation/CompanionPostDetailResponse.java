package com.duckmoim.companion.presentation;

import com.duckmoim.companion.domain.ClosedReason;
import com.duckmoim.companion.domain.PostStatus;
import com.duckmoim.companion.service.PostView;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 모집글 상세 (PO-11).
 *
 * <p><b>목록에서 {@code excerpt} 가 {@code content} 로 바뀌고 {@code createdAt} 이 늘어난다</b> (화면-계약.md 「모집글 ·
 * 댓글 (PO · CM)」). 그 밖의 필드는 목록 카드와 같다.
 *
 * <p><b>본문을 요청자에 따라 가리지 않는다.</b> 도메인-모델링.md 「7.1 가시성과 권한」의 표가 모집글 본문을 「비회원 및 회원 열람 가능」으로 두었고, PO-11
 * 의 검증 기준이 <i>"비인증 요청에도 본문 포함 200"</i> 이다. 가리는 것은 비밀 댓글 본문뿐이고 그것은 별도 조회다 (CM-06).
 *
 * <p><b>{@code closesAt} 을 두지 않는다.</b> 목데이터에 있던 필드인데 어느 문서에도 정의가 없다 — 마감은 {@code meetAt} 이 지나면 배치가
 * 거는 것이고 (PO-14) 별도 마감 시각이라는 개념이 설계에 없다 (화면-계약.md 「모집글 · 댓글 (PO · CM)」).
 *
 * @param content 본문 <b>전체</b>다. 선택 입력이라 null 일 수 있다 (PO-01)
 * @param createdAt 저장은 UTC 이고 응답은 KST 오프셋을 포함한다 (API-컨벤션.md 「필드 표기 규칙」)
 * @param commentCount 저장하지 않고 조회 때 센다 — 비밀 포함, 삭제·블라인드 제외, 대댓글 포함 (CM-12)
 */
public record CompanionPostDetailResponse(
    Long id,
    String eventId,
    String eventTitle,
    String eventImageUrl,
    String title,
    String content,
    PostStatus status,
    ClosedReason closedReason,
    Integer capacity,
    OffsetDateTime meetAt,
    OffsetDateTime createdAt,
    MeetPointResponse meetPoint,
    PostAuthorResponse author,
    long commentCount) {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  static CompanionPostDetailResponse from(PostView view) {
    return new CompanionPostDetailResponse(
        view.id(),
        view.eventId(),
        view.eventTitle(),
        view.eventImageUrl(),
        view.title(),
        view.content(),
        view.status(),
        view.closedReason(),
        view.capacity(),
        toKst(view.meetAt()),
        toKst(view.createdAt()),
        MeetPointResponse.from(view.meetPoint()),
        PostAuthorResponse.from(view),
        view.commentCount());
  }

  private static OffsetDateTime toKst(LocalDateTime storedInUtc) {
    return storedInUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toOffsetDateTime();
  }
}
