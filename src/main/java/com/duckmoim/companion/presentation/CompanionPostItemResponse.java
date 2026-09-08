package com.duckmoim.companion.presentation;

import com.duckmoim.companion.domain.ClosedReason;
import com.duckmoim.companion.domain.Excerpt;
import com.duckmoim.companion.domain.PostStatus;
import com.duckmoim.companion.service.PostView;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 목록 카드 한 장 (PO-08).
 *
 * <p><b>본문 대신 {@code excerpt} 를 싣는다.</b> 서버가 잘라서 내린다 — API-설계.md 「2-4. 모집글 (Companion)」이 이유를 적어
 * 두었다: <i>"카드에 두 줄만 보이는데 20건치 본문을 통째로 내리면 응답이 커진다."</i> 상세에서 {@code content} 로 바뀐다.
 *
 * <p><b>{@code createdAt} 이 없다.</b> 화면-계약.md 「모집글 · 댓글 (PO · CM)」의 목록 항목에 그 필드가 없고, 작성시각은 상세에서 늘어나는
 * 셋 중 하나다 (PO-11).
 *
 * @param eventId 붙은 행사의 <b>외부 식별자</b>다 (API-설계.md 「2-3. 행사 (Catalog)」). 숫자 PK 가 아니다
 * @param eventTitle 붙은 행사에서 복제해 둔 스냅샷이다. 조인이 아니라서 행사 정보가 나중에 바뀌어도 모집글은 자기 값으로 그려진다 (도메인-모델링.md 「3.2
 *     애그리게이트 간 참조 규칙」). 행사를 안 고른 글은 {@code eventImageUrl} 과 함께 null 이고, 카드는 그때 색 블록으로 떨어진다
 * @param closedReason 배지 문구를 정한다 — {@code MANUAL} 이면 「모집 완료」, {@code MEET_TIME_PASSED} 면 「종료」 다.
 *     {@code status} 만 받으면 둘을 구분할 수 없다
 * @param capacity 없으면 정원을 표시하지 않는다 (PO-05)
 * @param meetAt 저장은 UTC 이고 응답은 KST 오프셋을 포함한다 (API-컨벤션.md 「필드 표기 규칙」)
 */
public record CompanionPostItemResponse(
    Long id,
    String eventId,
    String eventTitle,
    String eventImageUrl,
    String title,
    String excerpt,
    PostStatus status,
    ClosedReason closedReason,
    Integer capacity,
    OffsetDateTime meetAt,
    MeetPointResponse meetPoint,
    PostAuthorResponse author,
    long commentCount) {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  static CompanionPostItemResponse from(PostView view) {
    return new CompanionPostItemResponse(
        view.id(),
        view.eventId(),
        view.eventTitle(),
        view.eventImageUrl(),
        view.title(),
        Excerpt.of(view.content()),
        view.status(),
        view.closedReason(),
        view.capacity(),
        toKst(view.meetAt()),
        MeetPointResponse.from(view.meetPoint()),
        PostAuthorResponse.from(view),
        view.commentCount());
  }

  private static OffsetDateTime toKst(LocalDateTime storedInUtc) {
    return storedInUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toOffsetDateTime();
  }
}
