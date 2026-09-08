package com.duckmoim.companion.service;

import com.duckmoim.companion.domain.ClosedReason;
import com.duckmoim.companion.domain.MeetPoint;
import com.duckmoim.companion.domain.PostStatus;
import com.duckmoim.identity.domain.LastSeen;
import java.time.LocalDateTime;

/**
 * 읽어 조립한 모집글 한 건.
 *
 * <p>엔티티를 그대로 presentation 으로 넘기지 않는다 (아키텍처-컨벤션.md 「service」).
 *
 * <p><b>목록과 상세가 같은 모양을 쓴다.</b> 갈리는 것은 본문을 잘라 싣는지 전부 싣는지뿐이라 (API-설계.md 「2-4. 모집글 (Companion)」) 여기는
 * 언제나 본문 전체를 담고, 자르는 것은 목록 응답의 일이다. 모양을 가르면 조립이 두 곳이 된다.
 *
 * @param eventId 붙은 행사의 <b>외부 식별자</b>다. 행사를 안 골랐으면 {@code null} 이고 그때는 {@code eventTitle} · {@code
 *     eventImageUrl} 도 함께 {@code null} 이다
 * @param content 선택 입력이라 {@code null} 일 수 있다 (PO-01)
 * @param closedReason 배지 문구를 정한다 — {@code MANUAL} 이면 「모집 완료」, {@code MEET_TIME_PASSED} 면 「종료」 다
 *     (화면-계약.md 「모집글 · 댓글 (PO · CM)」). {@code OPEN} 이면 {@code null}
 * @param capacity 없으면 정원을 표시하지 않는다 (PO-05)
 * @param meetAt 저장된 값 그대로 UTC 다. KST 오프셋을 붙이는 것은 응답 조립의 일이다
 * @param createdAt 같은 이유로 UTC 다
 * @param lastSeen 방장의 최근 접속 <b>구간</b>이다. 원본 시각은 어느 경로에도 나가지 않는다 (도메인-모델링.md 「7.2 최근 접속일 노출」). 관측된
 *     적이 없으면 {@code null}
 * @param commentCount 저장하지 않고 조회 때 센 값이다 (CM-12 · I-11)
 */
public record PostView(
    Long id,
    String eventId,
    String eventTitle,
    String eventImageUrl,
    String title,
    String content,
    PostStatus status,
    ClosedReason closedReason,
    Integer capacity,
    LocalDateTime meetAt,
    LocalDateTime createdAt,
    MeetPoint meetPoint,
    Long hostId,
    String nickname,
    String profileImageUrl,
    LastSeen lastSeen,
    long commentCount) {}
