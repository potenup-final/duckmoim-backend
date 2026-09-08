package com.duckmoim.companion.presentation;

import com.duckmoim.companion.domain.CommentAvailableAction;
import com.duckmoim.companion.domain.CommentStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 조회 경로가 내려주는 댓글 한 건 (API-설계.md 「2-5. 댓글 (Companion)」).
 *
 * <p>작성 응답인 {@link CommentResponse} 와 다른 타입이다. 작성은 자기가 쓴 것을 돌려받는 자리라 본문이 늘 있지만, 조회는 <b>요청자에 따라 본문이
 * 사라진다.</b>
 *
 * <p><b>{@code content} 는 값이 null 인 것이 아니라 키가 사라진다.</b> API-컨벤션.md 「필드 표기 규칙」이 <i>"null 이 될 수 있는
 * 필드는 응답에서 생략하지 않고 null 로 명시한다. 단, 권한에 따라 서버가 감추는 필드는 null 이 아니라 키 자체를 제거한다"</i> 고 갈라 놓았고, 비밀 댓글
 * 본문이 그 예외다 (CM-05 · I-07). 그래서 이 필드 하나에만 {@code @JsonInclude} 가 붙는다 — 클래스 전체에 걸면 다른 필드의 null 까지
 * 사라져 컨벤션의 앞 문장을 어긴다.
 *
 * <p>화면은 키가 있는지만 보고 「비밀」 배지를 그린다 (화면-계약.md).
 *
 * @param content 열람 권한이 없으면 <b>키째 빠진다.</b> {@code status} 가 ACTIVE 가 아닐 때도 마찬가지다 (CM-08 · CM-11)
 * @param createdAt 저장은 UTC 이고 응답은 KST 오프셋을 포함한다 (API-컨벤션.md 「필드 표기 규칙」)
 * @param availableActions 이 요청자가 이 댓글에 할 수 있는 것 (CM-18). 서버가 채운다
 * @param replies 대댓글. 루트가 아니면 언제나 비어 있다 — 깊이가 1단계로 고정이다 (I-06)
 */
public record CommentItemResponse(
    Long id,
    Long parentId,
    boolean secret,
    CommentStatus status,
    @JsonInclude(JsonInclude.Include.NON_NULL) String content,
    OffsetDateTime createdAt,
    CommentAuthorResponse author,
    List<CommentAvailableAction> availableActions,
    List<CommentItemResponse> replies) {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  static OffsetDateTime toKst(LocalDateTime storedInUtc) {
    return storedInUtc.atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).toOffsetDateTime();
  }
}
