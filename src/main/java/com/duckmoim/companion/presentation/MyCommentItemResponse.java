package com.duckmoim.companion.presentation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 내 댓글 내역이 내려주는 댓글 한 건 (CM-16 · AU-10).
 *
 * <p>{@link CommentItemResponse} 와 다른 타입이다. 목록은 <b>모집글 하나의 댓글 나무</b>를 내리므로 작성자 블록 · 대댓글 · 액션이 붙지만,
 * 내 내역은 <b>모집글을 가로지른 평평한 줄들</b>이라 그 셋 대신 어느 모집글에 쓴 것인지가 필요하다.
 *
 * <p>담기는 것은 화면-계약.md 의 내 내역 응답이 정한 대로다. 필드를 셋으로 줄이지 않고 {@code postId} · {@code secret} 을 함께 내리는 것은
 * 화면이 그 글로 이동하고 「비밀」 배지를 그려야 하기 때문이다 — CM-16 이 요구하는 셋(모집글 제목 · 본문 · 작성 시각)은 그 안에 들어 있다.
 *
 * <p><b>{@code replied} 는 넣지 않았다.</b> 화면 계약이 「넣을지 정해야 한다」로 남겨둔 필드이고 CM-16 의 요구 항목 밖이다. 넣으려면 자식 존재
 * 확인과 {@code parent_id} 선두 인덱스가 더 필요해서 별도 티켓으로 미뤘다.
 *
 * <p><b>{@code status} 도 없다.</b> 살아 있는 댓글만 실리기 때문이다 ({@code MyCommentListQuery}) — 값이 언제나 {@code
 * ACTIVE} 인 필드는 화면이 분기할 것이 없다.
 *
 * @param content 열람 권한이 없으면 <b>키째 빠진다</b> ({@code I-07} · CM-05). 내 내역에서는 삭제·블라인드가 이미 걸러져 실제로 사라지는
 *     경우가 없지만, 판정을 지나는 것이 계약이라 그 결과를 그대로 반영한다
 * @param createdAt 저장은 UTC 이고 응답은 KST 오프셋을 포함한다 (API-컨벤션.md 「필드 표기 규칙」)
 */
public record MyCommentItemResponse(
    Long id,
    Long postId,
    String postTitle,
    @JsonInclude(JsonInclude.Include.NON_NULL) String content,
    boolean secret,
    OffsetDateTime createdAt) {}
